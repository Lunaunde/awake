package com.example.awake.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.awake.data.local.TimetableEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSelector(items: List<TimetableEntity>, selectedId: Long?, onSelected: (Long) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    val selected = items.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded.value, onExpandedChange = { expanded.value = !expanded.value }) {
        OutlinedTextField(
            value = selected?.label ?: "暂无课表",
            onValueChange = {}, readOnly = true, label = { Text("当前课表") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded.value) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { expanded.value = false; onSelected(item.id) })
            }
        }
    }
}
