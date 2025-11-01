package com.example.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    private var isLoggedIn = mutableStateOf(false)
    private var userEmail = mutableStateOf("")
    private var isLoading = mutableStateOf(false)
    private var errorText = mutableStateOf("")

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Google Sign-In завершено з кодом: ${result.resultCode}")

        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)

            Log.d(TAG, "Google акаунт: ${account.email}")

            val idToken = account.idToken
            if (idToken != null) {
                Log.d(TAG, "Токен отримано, авторизація в Firebase...")
                authenticateWithFirebase(idToken)
            } else {
                Log.e(TAG, "ID Token = null!")
                isLoading.value = false
                errorText.value = "Помилка: не вдалося отримати токен"
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Помилка Google Sign-In: ${e.statusCode}", e)
            isLoading.value = false
            errorText.value = when (e.statusCode) {
                10 -> "Помилка 10: Перевірте SHA-1 fingerprint у Firebase"
                12501 -> "Вхід скасовано"
                else -> "Помилка входу: ${e.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== ЗАСТОСУНОК ЗАПУЩЕНО ===")

        auth = Firebase.auth
        firestore = Firebase.firestore

        val webClientId = try {
            getString(resources.getIdentifier("default_web_client_id", "string", packageName))
        } catch (e: Exception) {
            Log.e(TAG, "Web Client ID не знайдено!")
            ""
        }

        Log.d(TAG, "Web Client ID: $webClientId")

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val currentUser = auth.currentUser
        if (currentUser != null) {
            Log.d(TAG, "Користувач вже увійшов: ${currentUser.email}")
            isLoggedIn.value = true
            userEmail.value = currentUser.email ?: ""
        } else {
            Log.d(TAG, "Користувач не увійшов")
            isLoggedIn.value = false
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFFF5F5F5)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLoggedIn.value) {
                        MessageScreen()
                    } else {
                        LoginScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun LoginScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Ласкаво просимо!",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Firebase Messenger",
                fontSize = 18.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(64.dp))

            if (isLoading.value) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Вхід в систему...", fontSize = 16.sp)
            } else {
                Button(
                    onClick = {
                        Log.d(TAG, "Натиснуто кнопку входу")
                        startGoogleSignIn()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Увійти через Google",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (errorText.value.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        text = errorText.value,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    @Composable
    fun MessageScreen() {
        var inputText by remember { mutableStateOf("") }
        var currentMessage by remember { mutableStateOf("Завантаження...") }
        var statusText by remember { mutableStateOf("") }
        var isStatusError by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            Log.d(TAG, "Підписка на оновлення Firestore")

            firestore.collection("messages")
                .document("latest")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Помилка Firestore: ${error.message}", error)
                        currentMessage = "Помилка завантаження"
                        statusText = "Помилка: ${error.message}"
                        isStatusError = true
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val text = snapshot.getString("text")
                        currentMessage = text ?: "Немає повідомлень"
                        Log.d(TAG, "Повідомлення з Firestore: $text")
                    } else {
                        currentMessage = "Немає повідомлень"
                        Log.d(TAG, "Документ не знайдено")
                    }
                }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Інформація про користувача
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ви увійшли як:",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = userEmail.value,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1976D2)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Актуальне повідомлення
            Text(
                text = "Актуальне повідомлення:",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF9C4)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentMessage,
                        fontSize = 16.sp,
                        color = Color(0xFF333333)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Відправити повідомлення
            Text(
                text = "Відправити нове повідомлення:",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Введіть повідомлення") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    Log.d(TAG, "Натиснуто 'Відправити'")
                    if (inputText.isNotBlank()) {
                        saveMessageToFirestore(inputText) { success, error ->
                            if (success) {
                                statusText = "✓ Повідомлення відправлено!"
                                isStatusError = false
                                inputText = ""
                            } else {
                                statusText = "✗ Помилка: $error"
                                isStatusError = true
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color.LightGray
                )
            ) {
                Text(
                    text = "Відправити",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Статус відправлення
            if (statusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isStatusError)
                            Color(0xFFFFEBEE)
                        else
                            Color(0xFFC8E6C9)
                    )
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(12.dp),
                        color = if (isStatusError) Color(0xFFC62828) else Color(0xFF2E7D32),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Кнопка виходу
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "Натиснуто 'Вийти'")
                    logout()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFD32F2F)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 2.dp
                )
            ) {
                Text(
                    text = "Вийти з облікового запису",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    private fun startGoogleSignIn() {
        errorText.value = ""
        isLoading.value = true
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun authenticateWithFirebase(idToken: String) {
        Log.d(TAG, "Авторизація в Firebase...")

        val credential = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                Log.d(TAG, "✓ Firebase авторизація УСПІШНА")
                Log.d(TAG, "Користувач: ${user?.email}")

                isLoading.value = false
                errorText.value = ""
                isLoggedIn.value = true
                userEmail.value = user?.email ?: ""
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "✗ Firebase авторизація НЕВДАЛА", e)
                isLoading.value = false
                errorText.value = "Помилка Firebase: ${e.message}"
            }
    }

    private fun saveMessageToFirestore(text: String, callback: (Boolean, String?) -> Unit) {
        Log.d(TAG, "Збереження повідомлення в Firestore: $text")

        val data = hashMapOf(
            "text" to text,
            "timestamp" to System.currentTimeMillis(),
            "userId" to (auth.currentUser?.uid ?: "unknown"),
            "userEmail" to userEmail.value
        )

        firestore.collection("messages")
            .document("latest")
            .set(data)
            .addOnSuccessListener {
                Log.d(TAG, "✓ Повідомлення збережено")
                callback(true, null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "✗ Не вдалося зберегти повідомлення", e)
                callback(false, e.message)
            }
    }

    private fun logout() {
        Log.d(TAG, "Вихід з системи...")
        auth.signOut()
        googleSignInClient.signOut()
        isLoggedIn.value = false
        userEmail.value = ""
        errorText.value = ""
        Log.d(TAG, "✓ Вихід виконано")
    }
}