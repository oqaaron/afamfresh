package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Address
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AddressViewModel

/**
 * Saved delivery addresses: list, add, edit, delete, and choose a default.
 *
 * Reachable from Profile → Addresses, whose callback was previously an empty
 * lambda pointing at a screen that did not exist.
 */
@Composable
fun AddressesScreen(
    addressViewModel: AddressViewModel,
    onBack: () -> Unit
) {
    val addresses by addressViewModel.addresses.collectAsState()

    var editing by remember { mutableStateOf<Address?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Address?>(null) }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Addresses", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = null
                    showForm = true
                },
                containerColor = Forest
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add address", tint = Color.White)
            }
        }
    ) { padding ->
        if (addresses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = InkMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No saved addresses yet", fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Save an address once and you won't have to retype it at checkout.",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            editing = null
                            showForm = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Forest)
                    ) {
                        Text("ADD AN ADDRESS", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(addresses, key = { it.id }) { address ->
                    AddressCard(
                        address = address,
                        onEdit = {
                            editing = address
                            showForm = true
                        },
                        onDelete = { pendingDelete = address },
                        onMakeDefault = { addressViewModel.setDefault(address.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    if (showForm) {
        AddressFormDialog(
            initial = editing,
            isFirstAddress = addresses.isEmpty(),
            onDismiss = { showForm = false },
            onSave = { label, name, phone, area, line, isDefault ->
                addressViewModel.save(
                    existingId = editing?.id,
                    label = label,
                    recipientName = name,
                    phone = phone,
                    area = area,
                    addressLine = line,
                    isDefault = isDefault
                )
                showForm = false
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this address?") },
            text = { Text("\"${target.label}\" will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    addressViewModel.delete(target.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = Tomato, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AddressCard(
    address: Address,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(address.label, fontWeight = FontWeight.Bold, color = Ink)
                if (address.isDefault) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ForestSurface)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Default", color = Forest, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = InkMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Tomato, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(address.recipientName, fontSize = 14.sp, color = Ink)
        Text(address.summary, fontSize = 13.sp, color = InkMuted)
        if (address.phone.isNotBlank()) {
            Text(address.phone, fontSize = 13.sp, color = InkMuted)
        }

        if (!address.isDefault) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Set as default",
                color = Forest,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onMakeDefault() }
            )
        }
    }
}

@Composable
private fun AddressFormDialog(
    initial: Address?,
    isFirstAddress: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        label: String,
        recipientName: String,
        phone: String,
        area: String,
        addressLine: String,
        isDefault: Boolean
    ) -> Unit
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var name by remember { mutableStateOf(initial?.recipientName ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var area by remember { mutableStateOf(initial?.area ?: "") }
    var line by remember { mutableStateOf(initial?.addressLine ?: "") }
    // The very first address is always the default — there is nothing else it
    // could be — so the switch is forced on and disabled in that case.
    var isDefault by remember { mutableStateOf(initial?.isDefault ?: isFirstAddress) }

    val canSave = label.isNotBlank() && name.isNotBlank() && line.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add address" else "Edit address") },
        text = {
            Column {
                AddressField(label, { label = it }, "Label (Home, Work…)")
                AddressField(name, { name = it }, "Recipient name")
                AddressField(phone, { phone = it }, "Phone number")
                AddressField(area, { area = it }, "Area / neighbourhood")
                AddressField(line, { line = it }, "Street address / directions")

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isDefault,
                        onCheckedChange = { isDefault = it },
                        enabled = !isFirstAddress,
                        colors = SwitchDefaults.colors(checkedTrackColor = Forest)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (isFirstAddress) "Your first address is the default" else "Use as default",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(label, name, phone, area, line, isDefault) }
            ) {
                Text("Save", fontWeight = FontWeight.Bold, color = if (canSave) Forest else InkMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddressField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp)
    )
}
