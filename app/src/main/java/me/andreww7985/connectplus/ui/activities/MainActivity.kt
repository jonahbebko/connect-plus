package me.andreww7985.connectplus.ui.activities

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.android.synthetic.main.activity_main.*
import me.andreww7985.connectplus.App
import me.andreww7985.connectplus.R
import me.andreww7985.connectplus.dfu.DfuView
import me.andreww7985.connectplus.helpers.UIHelper
import me.andreww7985.connectplus.manager.SpeakerManager
import me.andreww7985.connectplus.speaker.ProductModel
import me.andreww7985.connectplus.speaker.SpeakerView
import me.andreww7985.connectplus.ui.fragments.ConnectFragment
import me.andreww7985.connectplus.ui.fragments.SettingsFragment
import timber.log.Timber

class MainActivity : AppCompatActivity(), BottomNavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val KEY_SELECTED_ITEM = "selectedItem"
    }

    private var selectedItem: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("onCreate")

        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        savedInstanceState.let {
            if (it == null) {
                updateCurrentFragment(R.id.nav_dashboard)
            } else {
                selectedItem = it.getInt(KEY_SELECTED_ITEM)
            }
        }

        nav_menu.setOnNavigationItemSelectedListener(this)

        /* Don't show DFU flash menu when speaker model is unknown, Flip 5 or Pulse 4 */
        val selectedSpeaker = SpeakerManager.selectedSpeaker
        if (selectedSpeaker == null ||
                selectedSpeaker.model == ProductModel.UNKNOWN ||
                selectedSpeaker.model == ProductModel.FLIP5 ||
                selectedSpeaker.model == ProductModel.PULSE4)
            nav_menu.menu.removeItem(R.id.nav_flash_dfu)
    }

    private fun updateCurrentFragment(selectedItemId: Int) {
        Timber.d("updateCurrentFragment")
        if (this.selectedItem == selectedItemId) return
        this.selectedItem = selectedItemId
        nav_menu.selectedItemId = selectedItemId

        val fragment = when (selectedItemId) {
            R.id.nav_dashboard -> SpeakerView()
            R.id.nav_connect -> ConnectFragment()
            R.id.nav_flash_dfu -> DfuView()
//            R.id.nav_settings -> SettingsFragment()
            else -> throw IllegalArgumentException("Wrong selectedItemId")
        }

        App.analytics.logEvent("opened_menu") {
            putString("menu_name", fragment.javaClass.simpleName)
        }

        UIHelper.showFragment(this, fragment)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_ITEM, selectedItem)
        super.onSaveInstanceState(outState)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        updateCurrentFragment(item.itemId)
        return true
    }
}