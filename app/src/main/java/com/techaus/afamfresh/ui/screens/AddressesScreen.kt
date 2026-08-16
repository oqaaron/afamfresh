package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.techaus.afamfresh.viewmodel.LocationViewModel

/**
 * Saved delivery addresses: list, add, edit, delete, and choose a default.
 *
 * Reachable from Profile → Addresses, whose callback was previously an empty
 * lambda pointing at a screen that did not exist.
 *
 * Integrates map-based location picker for diaspora users to precisely pin
 * their delivery locations before checkout or scheduling.
 */
@Composable
fun AddressesScreen(
    addressViewModel: AddressViewModel,
    locationViewModel: LocationViewModel,
    onBack: () -> Unit,
    onSelectLocation: () -> Unit
) {
    val addresses by addressViewModel.addresses.collectAsState()
    val pickedArea by locationViewModel.pickedArea.collectAsState()
    val pickedAddress by locationViewModel.pickedAddress.collectAsState()

    // The form's state lives here, not inside AddressFormDialog, and every
    // piece of it is rememberSaveable.
    //
    // Opening the map picker navigates to another destination, which takes this
    // whole screen out of composition. With plain `remember`, showForm reverted
    // to false and every typed field was discarded: the customer came back from
    // pinning their location to a CLOSED dialog, retyped the label and
    // recipient name, and only then saw the picked area appear. NavHost wraps
    // each destination in a SaveableStateHolder keyed by its back stack entry,
    // so rememberSaveable values here are restored on the way back — the dialog
    // is still open, still holding everything that was typed.
    //
    // editingId rather than the Address itself: a String survives saving
    // without needing a custom Saver, and the row is looked up from `addresses`
    // when it is actually needed.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var formLabel by rememberSaveable { mutableStateOf("") }
    var formName by rememberSaveable { mutableStateOf("") }
    var formPhone by rememberSaveable { mutableStateOf("") }
    var formArea by rememberSaveable { mutableStateOf("") }
    var formLine by rememberSaveable { mutableStateOf("") }
    var formIsDefault by rememberSaveable { mutableStateOf(false) }

    var pendingDelete by remember { mutableStateOf<Address?>(null) }
    // A save used to close the dialog immediately and discard the result, so a
    // rejected create looked exactly like a successful one: dialog gone, list
    // refreshed and unchanged, address silently missing.
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    /** Blank slate for "add", prefilled for "edit". */
    fun openForm(existing: Address?) {
        editingId = existing?.id
        formLabel = existing?.label ?: ""
        formName = existing?.recipientName ?: ""
        formPhone = existing?.phone ?: ""
        formArea = existing?.area ?: ""
        formLine = existing?.addressLine ?: ""
        // The first address is always the default — there is nothing else it
        // could be.
        formIsDefault = existing?.isDefault ?: addresses.isEmpty()
        saveError = null
        showForm = true
    }

    fun closeForm() {
        showForm = false
        editingId = null
        saveError = null
        locationViewModel.clearPickedLocation()
    }

    // Writes the picked location into the open form. Keyed on the picked values
    // so it fires exactly once per pin, rather than on every recomposition —
    // otherwise it would fight the customer for the cursor if they then edited
    // the area by hand.
    LaunchedEffect(pickedArea, pickedAddress) {
        pickedArea?.takeIf { it.isNotBlank() }?.let { formArea = it }
        pickedAddress?.takeIf { it.isNotBlank() }?.let { formLine = it }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Addresses", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openForm(null) },
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
                        onClick = { openForm(null) },
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
                        onEdit = { openForm(address) },
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
            isEditing = editingId != null,
            isFirstAddress = addresses.isEmpty(),
            label = formLabel,
            onLabelChange = { formLabel = it },
            recipientName = formName,
            onRecipientNameChange = { formName = it },
            phone = formPhone,
            onPhoneChange = { formPhone = it },
            area = formArea,
            onAreaChange = { formArea = it },
            addressLine = formLine,
            onAddressLineChange = { formLine = it },
            isDefault = formIsDefault,
            onIsDefaultChange = { formIsDefault = it },
            isSaving = isSaving,
            saveError = saveError,
            onDismiss = { closeForm() },
            onPickLocation = onSelectLocation,
            onSave = {
                isSaving = true
                saveError = null
                addressViewModel.save(
                    existingId = editingId,
                    label = formLabel,
                    recipientName = formName,
                    phone = formPhone,
                    area = formArea,
                    addressLine = formLine,
                    isDefault = formIsDefault
                ) { ok ->
                    // The dialog stays open on failure, holding everything the
                    // customer typed, instead of discarding it silently.
                    isSaving = false
                    if (ok) {
                        closeForm()
                    } else {
                        saveError = "Couldn't save that address. Check your connection and try again."
                    }
                }
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

/**
 * Stateless: every field is hoisted into [AddressesScreen], which holds them in
 * rememberSaveable so they survive the navigation to the map picker. Keeping
 * them in here as plain `remember` was what emptied the form on the way back.
 */
@Composable
private fun AddressFormDialog(
    isEditing: Boolean,
    isFirstAddress: Boolean,
    label: String,
    onLabelChange: (String) -> Unit,
    recipientName: String,
    onRecipientNameChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    area: String,
    onAreaChange: (String) -> Unit,
    addressLine: String,
    onAddressLineChange: (String) -> Unit,
    isDefault: Boolean,
    onIsDefaultChange: (Boolean) -> Unit,
    isSaving: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onPickLocation: () -> Unit,
    onSave: () -> Unit
) {
    // `area` is in this list because api/addresses.php rejects a create or
    // update outright when it is empty ("area and address_line are required").
    // Leaving it out let the button enable, fire a request the server was
    // always going to refuse, and drop the address on the floor.
    val canSave = label.isNotBlank() && recipientName.isNotBlank() &&
        addressLine.isNotBlank() && area.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!isEditing) "Add address" else "Edit address") },
        text = {
            Column {
                // Required fields are marked, because Save stays disabled until
                // all four are filled and there was previously nothing on screen
                // explaining why the button would not respond.
                AddressField(label, onLabelChange, "Label (Home, Work…) *")
                AddressField(recipientName, onRecipientNameChange, "Recipient name *")
                AddressField(phone, onPhoneChange, "Phone number")
                AddressField(area, onAreaChange, "Area / neighbourhood *")
                AddressField(addressLine, onAddressLineChange, "Street address / directions *")

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onPickLocation,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Location on Map", color = Color.White, fontWeight = FontWeight.Bold)
                }

                saveError?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(message, fontSize = 13.sp, color = Tomato)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isDefault,
                        onCheckedChange = onIsDefaultChange,
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
                enabled = canSave && !isSaving,
                onClick = onSave
            ) {
                Text(
                    if (isSaving) "Saving…" else "Save",
                    fontWeight = FontWeight.Bold,
                    color = if (canSave && !isSaving) Forest else InkMuted
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
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
