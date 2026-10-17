package com.posepocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.posepocket.ui.PocketPoserApp
import com.posepocket.ui.Workspace

class MainActivity : ComponentActivity() {
    private val model:PocketViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by model.state.collectAsState()
            PocketPoserApp(model.gate, onExit = { finish() },onRevoke=model::revokeRemote,ready={Workspace(model)},
                showChrome=ui.clip==null&&!ui.busy&&ui.recovery==null&&!ui.library&&ui.review==null)
        }
    }
}
