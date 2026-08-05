package com.cuetotech.vibetube.ui.auth

import android.util.Log
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.UserProfile
import com.cuetotech.vibetube.data.UserProfileRepository
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOGIN_ERROR_TAG = "LOGIN_ERROR"

data class AuthFormState(
    val isLoginMode: Boolean = true,
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val profileRepository: UserProfileRepository = UserProfileRepository(),
) : ViewModel() {

    private val _formState = MutableStateFlow(AuthFormState())
    val formState: StateFlow<AuthFormState> = _formState.asStateFlow()

    val user: StateFlow<FirebaseUser?> = authRepository.authState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUser())

    fun setLoginMode(isLogin: Boolean) {
        _formState.update { it.copy(isLoginMode = isLogin, error = null) }
    }

    fun onDisplayNameChange(value: String) {
        _formState.update { it.copy(displayName = value, error = null) }
    }

    fun onEmailChange(value: String) {
        _formState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _formState.update { it.copy(password = value, error = null) }
    }

    fun submit() {
        val state = _formState.value
        val email = state.email.trim()
        val password = state.password
        val displayName = state.displayName.trim()

        if (email.isEmpty() || password.isEmpty() || (!state.isLoginMode && displayName.isEmpty())) {
            _formState.update { it.copy(error = "Completa todos los campos") }
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _formState.update { it.copy(error = "Introduce un email válido") }
            return
        }
        if (password.length < 6) {
            _formState.update { it.copy(error = "La contraseña debe tener al menos 6 caracteres") }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, error = null) }
            try {
                if (state.isLoginMode) {
                    authRepository.signIn(email, password)
                } else {
                    val newUser = authRepository.signUp(email, password)
                    profileRepository.saveUserProfile(
                        UserProfile(
                            uid = newUser.uid,
                            displayName = displayName,
                            email = email,
                        ),
                    )
                }
                // Sesión iniciada (o cuenta creada): el StateFlow user pasa a
                // no-null y MainScreen muestra la pantalla principal. isLoading
                // se resetea para no dejar el botón bloqueado.
                _formState.update { it.copy(isLoading = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(LOGIN_ERROR_TAG, "Error en login", exception)
                _formState.update { it.copy(isLoading = false, error = exception.toUserMessage()) }
            }
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is FirebaseAuthUserCollisionException -> "Ya existe una cuenta con ese email"
        is FirebaseAuthInvalidUserException -> "No existe una cuenta con ese email"
        is FirebaseAuthInvalidCredentialsException -> "Email o contraseña incorrectos"
        is FirebaseAuthWeakPasswordException -> "La contraseña es demasiado débil"
        else -> message ?: "Error de autenticación"
    }
}
