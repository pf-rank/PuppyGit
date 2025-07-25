package com.catpuppyapp.puppygit.screen.functions

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import com.catpuppyapp.puppygit.constants.Cons
import com.catpuppyapp.puppygit.constants.LineNum
import com.catpuppyapp.puppygit.constants.PageRequest
import com.catpuppyapp.puppygit.dev.DevFeature
import com.catpuppyapp.puppygit.dto.UndoStack
import com.catpuppyapp.puppygit.fileeditor.texteditor.state.EditorStateOnChangeCallerFrom
import com.catpuppyapp.puppygit.fileeditor.texteditor.state.TextEditorState
import com.catpuppyapp.puppygit.git.DiffableItem
import com.catpuppyapp.puppygit.play.pro.R
import com.catpuppyapp.puppygit.screen.shared.CommitListFrom
import com.catpuppyapp.puppygit.screen.shared.DiffFromScreen
import com.catpuppyapp.puppygit.screen.shared.FileChooserType
import com.catpuppyapp.puppygit.screen.shared.FilePath
import com.catpuppyapp.puppygit.utils.AppModel
import com.catpuppyapp.puppygit.utils.FsUtils
import com.catpuppyapp.puppygit.utils.Libgit2Helper
import com.catpuppyapp.puppygit.utils.Msg
import com.catpuppyapp.puppygit.utils.MyLog
import com.catpuppyapp.puppygit.utils.UIHelper
import com.catpuppyapp.puppygit.utils.cache.NaviCache
import com.catpuppyapp.puppygit.utils.changeStateTriggerRefreshPage
import com.catpuppyapp.puppygit.utils.doJobThenOffLoading
import com.catpuppyapp.puppygit.utils.doJobWithMainContext
import com.catpuppyapp.puppygit.utils.generateRandomString
import com.catpuppyapp.puppygit.utils.getShortUUID
import com.catpuppyapp.puppygit.utils.replaceStringResList
import com.catpuppyapp.puppygit.utils.state.CustomStateSaveable
import com.catpuppyapp.puppygit.utils.withMainContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.withLock

private const val TAG = "ScreenHelper"

fun goToFileHistory(filePath: FilePath, activityContext: Context){
    goToFileHistory(filePath.toFuckSafFile(activityContext).canonicalPath, activityContext)
}

fun goToFileHistory(fileFullPath:String, activityContext: Context){
    doJobThenOffLoading job@{
        try {
            val repo = Libgit2Helper.findRepoByPath(fileFullPath)
            if(repo == null) {
                Msg.requireShow(activityContext.getString(R.string.no_repo_found))
                return@job
            }


            // file is belong a repo, but need check is under .git folder
            repo.use {
                //检查文件是否在.git目录下，若在，直接返回，此目录下的文件无历史记录
                val repoGitDirEndsWithSlash = Libgit2Helper.getRepoGitDirPathNoEndsWithSlash(it) + Cons.slash
                if(fileFullPath.startsWith(repoGitDirEndsWithSlash)) {
                    Msg.requireShowLongDuration(activityContext.getString(R.string.err_file_under_git_dir))
                    return@job
                }


                val repoWorkDirPath = Libgit2Helper.getRepoWorkdirNoEndsWithSlash(it)
                val relativePath = Libgit2Helper.getRelativePathUnderRepo(repoWorkDirPath, fileFullPath)
                if(relativePath == null) {  // this should never happen, cuz go to file history only available for file, not for dir, and this only happens when the realFullPath = repoWorkDirPath
                    Msg.requireShow(activityContext.getString(R.string.path_not_under_repo))
                    return@job
                }

                val repoDb = AppModel.dbContainer.repoRepository
                val repoFromDb = repoDb.getByFullSavePath(
                    repoWorkDirPath,
                    onlyReturnReadyRepo = false,  // if not ready, cant open at upside code, so reached here, no need more check about ready, is 100% ready
                    requireSyncRepoInfoWithGit = false,  // no need
                )

                if(repoFromDb == null) {
                    Msg.requireShowLongDuration(activityContext.getString(R.string.plz_import_repo_then_try_again))
                    return@job
                }

                goToFileHistoryByRelativePathWithMainContext(repoFromDb.id, relativePath)
            }

        }catch (e:Exception) {
            Msg.requireShowLongDuration(e.localizedMessage?:"err")
            MyLog.e(TAG, "#goToFileHistory err: ${e.stackTraceToString()}")
        }
    }
}

suspend fun goToFileHistoryByRelativePathWithMainContext(repoId:String, relativePathUnderRepo:String) {
    withMainContext {
        //go to file history page
        naviToFileHistoryByRelativePath(repoId, relativePathUnderRepo)
    }
}

fun naviToFileHistoryByRelativePath(repoId:String, relativePathUnderRepo:String) {
    val fileRelativePathKey = NaviCache.setThenReturnKey(relativePathUnderRepo)
    //go to file history page
    doJobWithMainContext {
        AppModel.navController.navigate(Cons.nav_FileHistoryScreen + "/" + repoId+"/"+fileRelativePathKey)
    }
}

fun getLoadText(loadedCount:Int, actuallyEnabledFilterMode:Boolean, activityContext:Context):String?{
    return if(loadedCount < 1){
        null
    }else if(actuallyEnabledFilterMode) {
        replaceStringResList(activityContext.getString(R.string.item_count_n), listOf(""+loadedCount))
    }else {
        replaceStringResList(activityContext.getString(R.string.loaded_n), listOf(""+loadedCount))
    }
}

fun getClipboardText(clipboardManager:ClipboardManager):String? {
    return try {
//       // or `clipboardManager.getText()?.toString()`
        clipboardManager.getText()?.text
    }catch (e:Exception) {
        MyLog.e(TAG, "#getClipboardText err: ${e.localizedMessage}")
        null
    }
}

fun openFileWithInnerSubPageEditor(
    context: Context,
    filePath:String,
    mergeMode:Boolean,
    readOnly:Boolean,
    goToLine:Int = LineNum.lastPosition,
    onlyGoToWhenFileExists: Boolean = false,
    showMsgIfGoToCanceledByFileNonExist:Boolean = true,
) {
    doJobWithMainContext job@{
        if(onlyGoToWhenFileExists && FilePath(filePath).toFuckSafFile(context).isActuallyReadable().not()) {
            if(showMsgIfGoToCanceledByFileNonExist){
                Msg.requireShowLongDuration(context.getString(R.string.file_doesnt_exist))
            }

            return@job
        }

        val filePathKey = NaviCache.setThenReturnKey(filePath)

        val initMergeMode = if(mergeMode) "1" else "0"
        val initReadOnly = if(readOnly) "1" else "0"


        AppModel.navController.navigate(Cons.nav_SubPageEditor + "/$goToLine/$initMergeMode/$initReadOnly/$filePathKey")
    }
}

/**
 * @param shortName short hash or tag name or branch name
 */
fun fromTagToCommitHistory(fullOid:String, shortName:String, repoId:String){
    goToCommitListScreen(
        repoId = repoId,
        fullOid = fullOid,
        shortBranchName = shortName,
        isHEAD = false,
        from = CommitListFrom.TAG,
    )
}



// topbar title text double-click functions start

fun defaultTitleDoubleClick(coroutineScope: CoroutineScope, listState: LazyListState, lastPosition: MutableState<Int>)  {
    UIHelper.switchBetweenTopAndLastVisiblePosition(coroutineScope, listState, lastPosition)
}

fun defaultTitleDoubleClick(coroutineScope: CoroutineScope, listState: ScrollState, lastPosition: MutableState<Int>)  {
    UIHelper.switchBetweenTopAndLastVisiblePosition(coroutineScope, listState, lastPosition)
}

fun defaultTitleDoubleClick(coroutineScope: CoroutineScope, listState: LazyStaggeredGridState, lastPosition: MutableState<Int>)  {
    UIHelper.switchBetweenTopAndLastVisiblePosition(coroutineScope, listState, lastPosition)
}

fun defaultTitleDoubleClickRequest(pageRequest: MutableState<String>) {
    pageRequest.value = PageRequest.switchBetweenTopAndLastPosition
}

// topbar title text double-click functions end

fun maybeIsGoodKeyword(keyword:String) : Boolean {
    return keyword.isNotEmpty()
}

fun filterModeActuallyEnabled(filterOn:Boolean, keyword: String):Boolean {
    return filterOn && maybeIsGoodKeyword(keyword)
}

fun <T> search(
    src:List<T>,
    match:(srcIdx:Int, srcItem:T)->Boolean,
    matchedCallback:(srcIdx:Int, srcItem:T)->Unit,
    canceled:()->Boolean
) {
    for(idx in src.indices){
        if(canceled()) {
            return
        }

        val it = src[idx]

        if(match(idx, it)) {
            matchedCallback(idx, it)
        }
    }
}

/**
 * @return canceled() 函数
 */
suspend fun initSearch(keyword: String, lastKeyword: MutableState<String>, token:MutableState<String>):()->Boolean {
    //更新上个关键字
    lastKeyword.value = keyword

    //生成新token
    val tokenForThisSession = generateNewTokenForSearch()
    //必须在主线程更新状态变量，不然可能获取到旧值，如果还有问题，改用Channel
    withMainContext {
        token.value = tokenForThisSession
    }

    //生成cancel函数并返回
    return {
        if(AppModel.devModeOn) {
            //若不相等，就是有bug，改用channel替换状态变量
            MyLog.v(TAG, "token.value==tokenForThisSession is '${token.value==tokenForThisSession}', if is false, may something wrong: token.value=${token.value}, tokenForThisSession=$tokenForThisSession")
        }

        //如果ide有 "Unused equals expression "，无视即可，ide不知道这个state变化后value会变，而curToken不会变，所以这个表达式并非常量
        //正常来说搜索前会生成新token，因此token必然非空，若为空，则代表取消搜索；若非空则与当前state变量进行比较，若不相等，代表开启了新的搜索，此次搜索已取消
        token.value.isEmpty() || tokenForThisSession != token.value
    }
}

fun generateNewTokenForSearch():String {
    return generateRandomString(18)
}

fun triggerReFilter(filterResultNeedRefresh:MutableState<String>) {
    filterResultNeedRefresh.value = getShortUUID()
}

@Composable
fun <T> filterTheList(
    activityContext: Context,

    needRefresh:String,
    lastNeedRefresh:MutableState<String>,

    enableFilter: Boolean,
    keyword: String,
    lastKeyword: MutableState<String>,
    searching: MutableState<Boolean>,
    token: MutableState<String>,
    resetSearchVars: () -> Unit,
    match:(idx:Int, item:T)->Boolean, // 若customTask非null，此参数无效
    list: List<T>,
    filterList: MutableList<T>,

    // 开始：file history 和 commit history用这几个变量
    lastListSize: MutableIntState? = null,
    filterIdxList:MutableList<Int>? = null,
    customTask:(suspend ()->Unit)? = null,  //若此参数非null，将忽略入参match，此参数内部应该完全自定义如何匹配条目
    // 结束：file history 和 commit history用这几个变量

    // commit history用到这个参数。若此参数返回真，则会重新执行搜索，可把附加的重新启用搜索的条件放到这个参数里执行
    orCustomDoFilterCondition:()->Boolean = {false},
    // commit history用这个参数。在搜索之前执行些附加操作，一般是清列表或者更新上次列表相关的变量
    //此函数调用的时机是已通过执行搜索的判断，但在执行搜索之前
    beforeSearchCallback:(()->Unit)? = null,
) : List<T> {
    return if (enableFilter) {
        val pageRefreshed = needRefresh != lastNeedRefresh.value;
        lastNeedRefresh.value = needRefresh

        val curListSize = list.size

        if (pageRefreshed || keyword != lastKeyword.value || (lastListSize != null && curListSize != lastListSize.intValue) || orCustomDoFilterCondition()) {
            lastListSize?.intValue = curListSize
            filterIdxList?.clear()
            beforeSearchCallback?.invoke()

            //若自定义任务为null则运行默认任务
            doJobThenOffLoading(loadingOff = { searching.value = false }) {
                // customTask若不为null，调用；若为null，调用默认task
                (customTask ?: {
                    val canceled = initSearch(keyword = keyword, lastKeyword = lastKeyword, token = token)

                    searching.value = true

                    filterList.clear()
                    search(src = list, match = match, matchedCallback = {idx, item -> filterList.add(item)}, canceled = canceled)
                }).invoke()
            }

        }

        filterList
    } else {
        resetSearchVars()
        list
    }

}

fun newScrollState(initial:Int = 0):ScrollState = ScrollState(initial = initial)

fun navToFileChooser(type: FileChooserType) {
    doJobWithMainContext {
        AppModel.navController.navigate(Cons.nav_FileChooserScreen + "/" + type.code)
    }
}

fun getFilesScreenTitle(currentPath:String, activityContext: Context):String {
    if(currentPath == FsUtils.rootPath) {
        return FsUtils.rootName
    }

    //不要在这判断path是否空字符串，后面的else会处理


    val trimedSlashCurPath = currentPath.trimEnd(Cons.slashChar)

    return if(trimedSlashCurPath == FsUtils.getInternalStorageRootPathNoEndsWithSeparator()) {
        activityContext.getString(R.string.internal_storage)
    }else if(trimedSlashCurPath == FsUtils.getExternalStorageRootPathNoEndsWithSeparator()) {
        activityContext.getString(R.string.external_storage)
    }else if(trimedSlashCurPath == FsUtils.getInnerStorageRootPathNoEndsWithSeparator()) {
        activityContext.getString(R.string.internal_storage)+"(Inner)"
    }else if(trimedSlashCurPath == AppModel.externalDataDir?.canonicalPath) {
        DevFeature.external_data_storage
    }else {
        runCatching { FsUtils.splitParentAndName(currentPath).second }.getOrDefault("").ifEmpty { activityContext.getString(R.string.files) }
    }
}

fun getEditorStateOnChange(
    editorPageTextEditorState:CustomStateSaveable<TextEditorState>,
//    lastTextEditorState:CustomStateSaveable<TextEditorState>,
    undoStack:UndoStack,
    resetLastCursorAtColumn:()->Unit,
): suspend (newState: TextEditorState, trueSaveToUndoFalseRedoNullNoSave:Boolean?, clearRedoStack:Boolean, caller: TextEditorState, from: EditorStateOnChangeCallerFrom?) -> Unit {
    return { newState, trueSaveToUndoFalseRedoNullNoSave, clearRedoStack, caller, from ->
        caller.codeEditor?.textEditorStateOnChangeLock?.withLock w@{
            val newState = if(from == EditorStateOnChangeCallerFrom.APPLY_SYNTAX_HIGHLIGHTING) {
                val latestState = editorPageTextEditorState.value
                if(
                    caller.fieldsId != latestState.fieldsId

                        // 这里不应该比较时间戳，只要不是当前状态，就不应该应用styles
                        // here shouldn't compare timestamp
//                            && FieldsId.parse(latestState.fieldsId).timestamp > FieldsId.parse(caller.fieldsId).timestamp
                ) {
                    //这个操作是editor state的apply syntax highlighting的方法调用的，但如今状态已经变化，这个状态并不是给最新的editor state准备的，所以取消应用，直接返回即可
                    MyLog.d(TAG, "editor state already changed, ignore style update request by previous style's apply syntax highlighting method")
                    return@w
                }

                // first copy for refresh view
                // even fieldsId are the same, other fields maybe are not same, so, we should copy latest state
                // 第一个拷贝是为了避免fields以外的状态丢失，例如：正在分析语法高亮样式，开启选中模式，由于没修改文本，所以fieldsId没变，
                //   样式分析完毕，执行到这里，如果应用调用onChanged那个实例传来的newState，选中模式等其他非fields字段就会变成调用者的而不是最新的状态的值
                if(latestState.focusingLineIdx.let { it == null || it >= 0 }) latestState.copy() else newState.copy(focusingLineIdx = latestState.focusingLineIdx)
            }else {
                //如果新state的focusingLineIdx为负数，使用上个state的focusingLineIdx，这样是为了避免 updateField 更新索引，不然会和 selectField 更新索引冲突，有时会定位错

                if(newState.focusingLineIdx.let { it == null || it >= 0 }) newState else newState.copy(focusingLineIdx = editorPageTextEditorState.value.focusingLineIdx)

            }


            val lastState = editorPageTextEditorState.value
            editorPageTextEditorState.value = newState

//            val lastState = lastTextEditorState.value

            // BEGIN: check whether need re analyzing the code syntax highlighting
            // 在点击undo然后编辑内容后 或者 增量分析出错时，重新执行全量分析
            // after "clicked undo then changed content" or incremental syntax highlighting thrown an err, do a full text re-analyze
//            val codeEditor = newState.codeEditor
//            val stylesMap = codeEditor.stylesMap
//            val latestStylesFieldsId = codeEditor.latestStyles?.fieldsId
//
//            // if true, the styles of syntax highlighting already detached, need re run the analyzing
//            if(latestStylesFieldsId != null && latestStylesFieldsId != caller.fieldsId && latestStylesFieldsId != newState.fieldsId && latestStylesFieldsId != lastState.fieldsId
//                && stylesMap.get(caller.fieldsId) == null && stylesMap.get(newState.fieldsId) == null && stylesMap.get(lastState.fieldsId) == null
//            ) {
//                // if haven't undo, the incremental syntax highlighting should working, so need not do a full text analyzing,
//                //   that means the program shouldn't reach here, if happened,
//                //   maybe something wrong, usually is TextEditorState's afterDelete/afterInsert/afterDeleteALineBreak methods err,
//                //   maybe just calculated a wrong CharPosition? idk, should check the code.
//                if(undoStack.redoStackIsEmpty()) {
//                    MyLog.w(TAG, "Detected the redo stack is empty, so maybe you haven't did an undo action? but now we need re-analyze full text for syntax highlighting, if you are not changed the syntax highlighting language or haven't reload the file, maybe the incremental syntax highlighting got some errs, please check the log and search keyword 'AsyncAnalyzer-' to find any err.")
//                    // if latest fields id doesn't matched any of last/current/next, maybe have some bugs
//                    MyLog.w(TAG, "latestStylesFieldsId: ${codeEditor.latestStyles?.fieldsId}, lastFieldsId: ${lastState.fieldsId}, currentFieldsId: ${caller.fieldsId}, nextFieldsId: ${newState.fieldsId}, from=$from")
//                }
//
//
//                codeEditor.analyze(newState)
//            }
            // END: check whether need re analyzing the code syntax highlighting


            // last state == null || 不等于新state的filesId，则入栈
            //这个fieldsId只是个粗略判断，即使一样也不能保证fields完全一样
            if(lastState.maybeNotEquals(newState)) {
                // if content changed, reset remembered last cursor at column，then next time navigate line by Down/Up key will update the column to the expect value
                // 如果内容改变，重置记录的光标所在列，这样下次按键盘上下键导航的位置就会重新更新为期望的值
                resetLastCursorAtColumn()
//                    if(lastTextEditorState.value?.fields != newState.fields) {
                //true或null，存undo; false存redo。null本来是在选择行之类的场景的，没改内容，可以不存，但我后来感觉存上比较好
//                if(trueSaveToUndoFalseRedoNullNoSave == null) {
//                    //之前存这个是因为text editor state携带了语法高亮数据，后来改成用fieldsId关联外部数据了，
//                    //  所以不需要再更新undo stack head了，就算携带数据，如果共享同一个对象实例，editor state只存引用，也不需要更新undo stack head
//                    // fields has not changed, but maybe other state changed, so need update
//                    undoStack.updateUndoHeadIfNeed(newState)
//                } else
                val saved = if(trueSaveToUndoFalseRedoNullNoSave != false) {  // null or true
                    // redo的时候，添加状态到undo，不清redo stack，平时编辑文件的时候更新undo stack需清空redo stack
                    // trueSaveToUndoFalseRedoNullNoSave为null时是选择某行之类的不修改内容的状态变化，因此不用清redoStack
//                        if(trueSaveToUndoFalseRedoNullNoSave!=null && clearRedoStack) {
                    //改了下调用函数时传的这个值，在不修改内容时更新状态清除clearReadStack传了false，所以不需要额外判断trueSaveToUndoFalseRedoNullNoSave是否为null了
                    if(clearRedoStack) {
                        undoStack.redoStackClear()
                    }

                    undoStack.undoStackPush(lastState)
                }else {  // false
                    undoStack.redoStackPush(lastState)
                }

//                if(saved) {
//                    lastTextEditorState.value = newState
//                }
            }

        }
    }
}

//初始状态值随便填，只是为了帮助泛型确定类型并且避免使用null作为初始值而已，具体的值在打开文件后会重新创建
fun getInitTextEditorState() = TextEditorState(
//    uniId = "",
    codeEditor = null,
    fields = listOf(),
    fieldsId = "",
    isContentEdited = mutableStateOf(false),
    editorPageIsContentSnapshoted = mutableStateOf(false),
    isMultipleSelectionMode = false,
    focusingLineIdx = null,
    onChanged = { i1, i2, i3, _, _ ->},
)

suspend fun goToStashPage(repoId:String) {
    withMainContext {
        AppModel.navController.navigate(Cons.nav_StashListScreen+"/"+repoId)
    }
}

fun goToTreeToTreeChangeList(title:String, repoId: String, commit1:String, commit2:String, commitForQueryParents:String) {
    doJobWithMainContext {
        val commit1OidStrCacheKey = NaviCache.setThenReturnKey(commit1)
        val commit2OidStrCacheKey = NaviCache.setThenReturnKey(commit2)
        val commitForQueryParentsCacheKey = NaviCache.setThenReturnKey(commitForQueryParents)
        //当前比较的描述信息的key，用来在界面显示这是在比较啥，值例如“和父提交比较”或者“比较两个提交”之类的
        val titleCacheKey = NaviCache.setThenReturnKey(title)

        // url 参数： 页面导航id/repoId/treeoid1/treeoid2/desckey
        AppModel.navController.navigate(
            //注意是 parentTreeOid to thisObj.treeOid，也就是 旧提交to新提交，相当于 git diff abc...def，比较的是旧版到新版，新增或删除或修改了什么，反过来的话，新增删除之类的也就反了
            "${Cons.nav_TreeToTreeChangeListScreen}/$repoId/$commit1OidStrCacheKey/$commit2OidStrCacheKey/$commitForQueryParentsCacheKey/$titleCacheKey"
        )
    }
}

fun goToCommitListScreen(repoId: String, fullOid:String, from: CommitListFrom, shortBranchName:String, isHEAD:Boolean) {
    doJobWithMainContext {
        val fullOidCacheKey = NaviCache.setThenReturnKey(fullOid)
        val shortBranchNameCacheKey = NaviCache.setThenReturnKey(shortBranchName)

        //注：如果fullOidKey传null，会变成字符串 "null"，然后查不出东西，返回空字符串，与其在导航组件取值时做处理，不如直接传空字符串，不做处理其实也行，只要“null“作为cache key取不出东西就行，但要是不做处理万一字符串"null"作为cache key能查出东西，就歇菜了，总之，走正常流程取得有效cache key，cache value传空字符串，即可
        AppModel.navController.navigate(Cons.nav_CommitListScreen + "/" + repoId + "/" + (if(isHEAD) "1" else "0")+"/"+ from.code +"/"+fullOidCacheKey+"/"+shortBranchNameCacheKey)
    }
}

fun goToDiffScreen(
//    relativePathList:List<String>,
    diffableList:List<DiffableItem>,
    repoId: String,
    fromTo: String,
    commit1OidStr:String,
    commit2OidStr:String,
    isDiffToLocal:Boolean,
    curItemIndexAtDiffableList:Int,
    localAtDiffRight:Boolean,
    fromScreen:String,
) {
    doJobWithMainContext {
//        val relativePathCacheKey = NaviCache.setThenReturnKey(relativePathList)

        //设置条目列表
//        val invalidCacheKey = getRandomUUID(10)
        //等于null说明目标页面不需要此列表，所以不用设置
        val diffableListCacheKey = NaviCache.setThenReturnKey(diffableList) ;

//        val isMultiMode = if(fromScreen != DiffFromScreen.FILE_HISTORY.code) 1 else 0
        val isMultiMode:Boolean = if(DiffFromScreen.isFromFileHistory(fromScreen)) false else !DevFeature.singleDiff.state.value;

        AppModel.navController.navigate(
            Cons.nav_DiffScreen +
                    "/" + repoId +
                    "/" + fromTo +
                    "/" + commit1OidStr +
                    "/" + commit2OidStr +
                    "/" + (if(isDiffToLocal) 1 else 0)
                    + "/" + curItemIndexAtDiffableList
                    +"/" + (if(localAtDiffRight) 1 else 0)

                    +"/" + fromScreen

                    +"/"+diffableListCacheKey
                    +"/"+(if(isMultiMode) 1 else 0)
        )
    }
}

/**
 * repoId 若为空字符串则代表新建，否则编辑已存在仓库
 */
fun goToCloneScreen(repoId: String="") {
    doJobWithMainContext {
        AppModel.navController.navigate(
            Cons.nav_CloneScreen+"/${repoId.ifBlank { Cons.dbInvalidNonEmptyId }}"
        )
    }
}


fun getFilesGoToPath(
    lastPressedPath: MutableState<String>,
    getCurrentPath:()->String,
    updateCurrentPath:(String)->Unit,
    needRefresh: MutableState<String>
): (String)->Unit {
    val funName = "getFilesGoToPath"

    return { newPath:String ->
        val oldPath = getCurrentPath()

        try {
            try {
                //为了找上次路径的结束位置的起始索引
                val startIndexForFindEndOfLastPath = newPath.length + 1;
                lastPressedPath.value = if(newPath.length < oldPath.length && startIndexForFindEndOfLastPath < oldPath.length && oldPath.startsWith(newPath)) {
                    // e.g. newPath = /abc/def, oldPath = /abc/def/ghi,
                    // newPath.length+1, so the cursor at 'g', which is our expect to find the target path
                    //这里不可能越界，因为 if 有判断 `startIndexForFindEndOfLastPath < oldPath.length`
                    val indexOfSlash = oldPath.indexOf(Cons.slashChar, startIndex = startIndexForFindEndOfLastPath)
                    //截出当前目录在新目录中的path，例如当前目录为 /abc/def 新目录为 /abc，则找到def，若当前目录为 /abc/def/ghi，新目录为 /abc，则截取 /abc/def
                    oldPath.substring(0, if(indexOfSlash < 0) oldPath.length else indexOfSlash)
                } else {
                    oldPath
                }
            }catch (e: Exception) {
                MyLog.d(TAG, "#$funName: resolve `lastPressedPath` failed! oldPath='$oldPath', newPath='$newPath', err=${e.stackTraceToString()}")
            }

            if(AppModel.devModeOn) {
                MyLog.v(TAG, "#$funName: lastPressedPath: ${lastPressedPath.value}")
            }

            updateCurrentPath(newPath)
            changeStateTriggerRefreshPage(needRefresh)
        }catch (e: Exception) {
            MyLog.e(TAG, "#$funName: change path failed! oldPath='$oldPath', newPath='$newPath', err=${e.stackTraceToString()}")
            Msg.requireShowLongDuration("err: change dir failed")
        }

    }
}

fun goToErrScreen(repoId:String) {
    doJobWithMainContext {
        AppModel.navController.navigate(Cons.nav_ErrorListScreen + "/" + repoId)
    }
}
