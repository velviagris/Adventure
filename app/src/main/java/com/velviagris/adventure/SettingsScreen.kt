package com.velviagris.adventure

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val isDailySummaryEnabled by viewModel.isDailySummaryEnabled.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleDailySummary(context, true)
        } else {
            Toast.makeText(context, "Notification permission is required for daily summary.", Toast.LENGTH_SHORT).show()
        }
    }

    val toastExportSuccess = stringResource(R.string.toast_export_success)
    val toastExportFail = stringResource(R.string.toast_export_fail)
    val toastExportLogsSuccess = stringResource(R.string.toast_export_logs_success)
    val toastExportLogsFail = stringResource(R.string.toast_export_logs_fail)
    val toastImportFail = stringResource(R.string.toast_import_fail)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportData(context.contentResolver, it) { success ->
                Toast.makeText(
                    context,
                    if (success) toastExportSuccess else toastExportFail,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            viewModel.exportLogs(context.contentResolver, it) { success ->
                Toast.makeText(
                    context,
                    if (success) toastExportLogsSuccess else toastExportLogsFail,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importData(context.contentResolver, it) { success, count ->
                val msg = if (success) {
                    context.getString(R.string.toast_import_success, count)
                } else {
                    toastImportFail
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = stringResource(R.string.settings_notifications),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_summary_title)) },
                supportingContent = { Text(stringResource(R.string.settings_summary_desc)) },
                trailingContent = {
                    Switch(
                        checked = isDailySummaryEnabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                if (
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.toggleDailySummary(context, true)
                                }
                            } else {
                                viewModel.toggleDailySummary(context, false)
                            }
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

            Text(
                text = stringResource(R.string.settings_data_management),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export_title)) },
                supportingContent = { Text(stringResource(R.string.settings_export_desc)) },
                leadingContent = {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable {
                    val fileName = "Adventure_Backup_${
                        SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                    }.json"
                    exportLauncher.launch(fileName)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_import_title)) },
                supportingContent = { Text(stringResource(R.string.settings_import_desc)) },
                leadingContent = {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable {
                    importLauncher.launch(arrayOf("application/json"))
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export_logs_title)) },
                supportingContent = { Text(stringResource(R.string.settings_export_logs_desc)) },
                leadingContent = {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable {
                    val fileName = "Adventure_Logs_${
                        SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                    }.txt"
                    exportLogsLauncher.launch(fileName)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = stringResource(R.string.settings_version),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .wrapContentWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
