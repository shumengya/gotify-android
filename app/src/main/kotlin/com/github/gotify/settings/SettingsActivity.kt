package com.github.gotify.settings

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.github.gotify.R
import com.github.gotify.Utils
import com.github.gotify.databinding.SettingsActivityBinding
import com.github.gotify.service.WebSocketService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class SettingsActivity :
    AppCompatActivity(),
    OnSharedPreferenceChangeListener {
    private lateinit var binding: SettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        setSupportActionBar(binding.appBarDrawer.toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowCustomEnabled(true)
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null) return
        when (key) {
            getString(R.string.setting_key_theme) -> {
                ThemeHelper.setTheme(
                    this,
                    sharedPreferences.getString(key, getString(R.string.theme_default))!!
                )
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                findPreference<SwitchPreferenceCompat>(
                    getString(R.string.setting_key_notification_channels)
                )?.isEnabled = true
            }
            findPreference<androidx.preference.EditTextPreference>(
                getString(R.string.setting_key_reconnect_delay)
            )?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as String).trim().toIntOrNull() ?: 60
                    if (value !in 5..1200) {
                        Utils.showSnackBar(
                            requireActivity(),
                            getString(R.string.setting_keepalive_hint_range)
                        )
                        return@OnPreferenceChangeListener false
                    }

                    requestWebSocketRestart()
                    true
                }
            findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_exponential_backoff)
            )?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    requestWebSocketRestart()
                    true
                }
            findPreference<Preference>(
                getString(R.string.setting_key_request_battery_optimization)
            )?.setOnPreferenceClickListener {
                requestIgnoreBatteryOptimizations()
            }
            findPreference<Preference>(
                getString(R.string.setting_key_keepalive_help)
            )?.setOnPreferenceClickListener {
                showKeepAliveGuide()
            }
        }

        private fun requestWebSocketRestart() {
            WebSocketService.start(requireContext())
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            findPreference<ListPreference>(
                getString(R.string.setting_key_message_layout)
            )?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    showRestartDialog()
                    true
                }
            findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_notification_channels)
            )?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        return@OnPreferenceChangeListener false
                    }
                    showRestartDialog()
                    true
                }
            findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_exclude_from_recent)
            )?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, value ->
                    Utils.setExcludeFromRecent(requireContext(), value as Boolean)
                    return@OnPreferenceChangeListener true
                }
            findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_intent_dialog_permission)
            )?.let {
                it.setOnPreferenceChangeListener { _, _ ->
                    openSystemAlertWindowPermissionPage()
                }
            }
            checkSystemAlertWindowPermission()
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference) {
                showListPreferenceDialog(preference)
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }

        override fun onResume() {
            super.onResume()
            checkSystemAlertWindowPermission()
        }

        private fun openSystemAlertWindowPermissionPage(): Boolean {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${requireContext().packageName}".toUri()
            ).apply {
                startActivity(this)
            }
            return true
        }

        private fun checkSystemAlertWindowPermission() {
            findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_intent_dialog_permission)
            )?.let {
                val canDrawOverlays = Settings.canDrawOverlays(requireContext())
                it.isChecked = canDrawOverlays
                it.summary = if (canDrawOverlays) {
                    getString(R.string.setting_summary_intent_dialog_permission_granted)
                } else {
                    getString(R.string.setting_summary_intent_dialog_permission)
                }
            }
        }

        private fun requestIgnoreBatteryOptimizations(): Boolean {
            return try {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${requireContext().packageName}".toUri()
                ).apply {
                    startActivity(this)
                }
                true
            } catch (_: ActivityNotFoundException) {
                openAppDetailsSettingsPage()
            }
        }

        private fun openAppDetailsSettingsPage(): Boolean {
            return try {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${requireContext().packageName}".toUri()
                ).apply {
                    startActivity(this)
                }
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }

        private fun showKeepAliveGuide(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val messageId = if (
                manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco")
            ) {
                R.string.setting_keepalive_guide_xiaomi
            } else {
                R.string.setting_keepalive_guide_generic
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_keepalive_help_title)
                .setMessage(messageId)
                .setPositiveButton(R.string.setting_open_battery_optimization) { _, _ ->
                    requestIgnoreBatteryOptimizations()
                }
                .setNeutralButton(R.string.setting_open_app_settings) { _, _ ->
                    openAppDetailsSettingsPage()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }

        private fun showListPreferenceDialog(preference: ListPreference) {
            val dialogFragment = MaterialListPreference()
            dialogFragment.arguments = Bundle(1).apply { putString("key", preference.key) }
            @Suppress("DEPRECATION") // https://issuetracker.google.com/issues/181793702#comment3
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(
                parentFragmentManager,
                "androidx.preference.PreferenceFragment.DIALOG"
            )
        }

        private fun showRestartDialog() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_restart_dialog_title)
                .setMessage(R.string.setting_restart_dialog_message)
                .setPositiveButton(getString(R.string.setting_restart_dialog_button1)) { _, _ ->
                    restartApp()
                }
                .setNegativeButton(getString(R.string.setting_restart_dialog_button2), null)
                .show()
        }

        private fun restartApp() {
            val packageManager = requireContext().packageManager
            val packageName = requireContext().packageName
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            val componentName = intent!!.component
            val mainIntent = Intent.makeRestartActivityTask(componentName)
            startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    class MaterialListPreference : ListPreferenceDialogFragmentCompat() {
        private var mWhichButtonClicked = 0

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            mWhichButtonClicked = DialogInterface.BUTTON_NEGATIVE
            val builder = MaterialAlertDialogBuilder(requireActivity())
                .setTitle(preference.dialogTitle)
                .setPositiveButton(preference.positiveButtonText, this)
                .setNegativeButton(preference.negativeButtonText, this)

            val contentView = context?.let { onCreateDialogView(it) }
            if (contentView != null) {
                onBindDialogView(contentView)
                builder.setView(contentView)
            } else {
                builder.setMessage(preference.dialogMessage)
            }
            onPrepareDialogBuilder(builder)
            return builder.create()
        }

        override fun onClick(dialog: DialogInterface, which: Int) {
            mWhichButtonClicked = which
        }

        override fun onDismiss(dialog: DialogInterface) {
            onDialogClosedWasCalledFromOnDismiss = true
            super.onDismiss(dialog)
        }

        private var onDialogClosedWasCalledFromOnDismiss = false

        override fun onDialogClosed(positiveResult: Boolean) {
            if (onDialogClosedWasCalledFromOnDismiss) {
                onDialogClosedWasCalledFromOnDismiss = false
                super.onDialogClosed(mWhichButtonClicked == DialogInterface.BUTTON_POSITIVE)
            } else {
                super.onDialogClosed(positiveResult)
            }
        }
    }
}
