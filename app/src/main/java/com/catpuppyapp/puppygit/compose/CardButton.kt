package com.catpuppyapp.puppygit.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.catpuppyapp.puppygit.ui.theme.Theme
import com.catpuppyapp.puppygit.utils.UIHelper



@Composable
fun CardButton(
    modifier: Modifier = Modifier,
//    paddingValues: PaddingValues = PaddingValues(30.dp),
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val inDarkTheme = Theme.inDarkTheme

    CardButton(
        modifier = modifier,
        enabled = enabled,
        buttonHeight = 50,
        content = {
            Text(
                text = text,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = UIHelper.getCardButtonTextColor(enabled, inDarkTheme)
            )
        },
        onClick = onClick
    )
}


@Composable
fun CardButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonHeight:Int? = null,  //null = no limit
    content:@Composable RowScope.()->Unit,
    onClick: () -> Unit
) {

    val cardColor = UIHelper.defaultCardColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)  //顶部加点margin，不然有时候显示不全
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            //0.9f 占父元素宽度的百分之90
            modifier = Modifier
                .clickable(enabled = enabled) {
                    onClick()
                },
            colors = CardDefaults.cardColors(
                containerColor = cardColor,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 6.dp
            )

        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(.85f)
                    .then(
                        //如果高度不为null，限制高度；否则不限
                        if(buttonHeight != null) {
                            Modifier.height(buttonHeight.dp)
                        }else{
                            Modifier
                        }
                    )
                    .padding(5.dp)
                ,

                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }

        }

//        Spacer(modifier = Modifier.height(95.dp))

    }

}
