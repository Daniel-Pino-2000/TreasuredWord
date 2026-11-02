package com.application.bibleapp.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

/** Tells Android's autofill what a text field holds (#169), so the keyboard can offer an emailed
 *  code and a password manager can fill or save the account's email and password. */
fun Modifier.autofill(type: ContentType): Modifier = semantics { contentType = type }

/** The account's email: it's also the sign-in name, so password managers save it as the username. */
val AccountEmail: ContentType = ContentType.Username + ContentType.EmailAddress
