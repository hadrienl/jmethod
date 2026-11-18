package fr.jmethod.connectionpoc

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.UUID

class MainActivity:ComponentActivity() {
    private var browser:WebView?=null
    private val vault by lazy { TokenVault(this) }
    private var client:PronoteClient?=null
    private var activeJob:Job?=null
    private var watchdog:Job?=null
    @Volatile private var generation=0
    private var nonce=""
    private var pin=""
    private var origin=""
    private var demo=false
    private var claimed=false
    private var injected=false
    /** Identifiants saisis dans le formulaire natif : uniquement en mémoire, remis à null dès leur unique transmission à la page officielle. */
    private var pendingCredentials:Pair<String,String>?=null
    private var credentialMode=false
    private var address by mutableStateOf("")
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var automationStopped by mutableStateOf(false)
    private var message by mutableStateOf("Connecte ton compte pour vérifier la connexion native à Pronote.")
    private var status by mutableStateOf("Prêt à commencer")
    private var showBrowser by mutableStateOf(false)
    private var browserStarted by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var account by mutableStateOf("")
    private var lessons by mutableStateOf<List<Lesson>>(emptyList())
    private var children by mutableStateOf<List<Child>>(emptyList())
    private var selectedChild by mutableStateOf("")
    private var startDemo=false
    private var startNativeDemo=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startDemo=intent?.data?.toString()=="jmethodpoc://demo"
        startNativeDemo=intent?.data?.toString()=="jmethodpoc://native-demo"
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF6D28D9))) { Surface(Modifier.fillMaxSize()) { Content() } } }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);if(intent.data?.toString()=="jmethodpoc://demo")openDemo()
        else if(intent.data?.toString()=="jmethodpoc://native-demo")nativeDemo() }
    @OptIn(ExperimentalLayoutApi::class) @Composable private fun Content() {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text("J-Method",style=MaterialTheme.typography.headlineLarge)
            Text("Connexion native · POC Android",style=MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(status,style=MaterialTheme.typography.titleLarge)
            Text(message,style=MaterialTheme.typography.bodyMedium)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(browserStarted)TextButton(onClick={showBrowser=!showBrowser}){Text(if(showBrowser)"Masquer le navigateur" else "Afficher le navigateur")}
                if(browserStarted&&automationStopped&&!busy)TextButton(onClick={resumeAutomation()}){Text("Reprendre l’association")}
                if(busy||browserStarted)TextButton(onClick={cancel()}){Text("Annuler")}
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory={ createBrowser().also { browser=it;if(startDemo){startDemo=false;it.post { openDemo() }};if(startNativeDemo){startNativeDemo=false;it.post { nativeDemo() }} } },modifier=Modifier.fillMaxSize(),update={ it.alpha=if(showBrowser)1f else 0f;it.isEnabled=showBrowser;it.importantForAccessibility=if(showBrowser)android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
                if(!showBrowser)Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!browserStarted && account.isBlank()) LoginForm()
                    if(account.isNotBlank()) {
                        Text(account,style=MaterialTheme.typography.titleLarge)
                        Row { TextButton(onClick={refresh()},enabled=!busy){Text("Actualiser")};TextButton(onClick={forget()}){Text("Oublier le compte")} }
                        children.forEach { child -> FilterChip(selected=selectedChild==child.id,onClick={selectedChild=child.id;refresh()},label={Text(child.name)}) }
                        Text("Les sept prochains jours",style=MaterialTheme.typography.titleMedium)
                        if(lessons.isEmpty()&&!busy)Text("Aucun cours transmis sur cette période.")
                        lessons.forEach { lesson -> Card(Modifier.fillMaxWidth().padding(vertical=5.dp)) { Column(Modifier.padding(14.dp)) {
                            Text(lesson.subject,style=MaterialTheme.typography.titleMedium)
                            Text(lesson.start.replace("T"," · ").take(18)+(lesson.end.substringAfter("T","").take(5).takeIf { it.isNotEmpty() }?.let { " – $it" } ?: ""))
                            if(lesson.teacher.isNotBlank())Text(lesson.teacher)
                            if(lesson.room.isNotBlank())Text(lesson.room)
                            if(lesson.cancelled)Text("Cours annulé",color=MaterialTheme.colorScheme.error)
                        } } }
                    }
                }
            }
        }
    }
    @Composable private fun LoginForm() {
        val canSubmit=!busy&&address.isNotBlank()&&username.isNotBlank()&&password.isNotEmpty()
        OutlinedTextField(address,{address=it},label={Text("Adresse de ton espace Pronote")},placeholder={Text("https://…index-education.net/pronote/eleve.html")},modifier=Modifier.fillMaxWidth(),singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false,imeAction=ImeAction.Next))
        OutlinedTextField(username,{username=it},label={Text("Identifiant")},modifier=Modifier.fillMaxWidth().semantics{contentType=ContentType.Username},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text,autoCorrectEnabled=false,imeAction=ImeAction.Next))
        OutlinedTextField(password,{password=it},label={Text("Mot de passe")},modifier=Modifier.fillMaxWidth().semantics{contentType=ContentType.Password},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false,imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={if(canSubmit)submitCredentials()}))
        Text("Tes identifiants sont transmis uniquement à la page officielle de Pronote et ne sont jamais enregistrés.",style=MaterialTheme.typography.bodySmall)
        Button(onClick={submitCredentials()},enabled=canSubmit,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Se connecter")}
        OutlinedButton(onClick={open(address,false)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Ouvrir la page Pronote officielle")}
        OutlinedButton(onClick={openDemo()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Tester la démonstration publique")}
        OutlinedButton(onClick={nativeDemo()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Tester uniquement le protocole natif public")}
        if(vault.load()!=null)TextButton(onClick={restore()}){Text("Reprendre la session enregistrée")}
    }
    private fun submitCredentials() {
        val login=username.trim();val secret=password
        if(busy||login.isEmpty()||secret.isEmpty())return
        open(address,false,login to secret)
    }
    @SuppressLint("SetJavaScriptEnabled") private fun createBrowser()=WebView(this).apply {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
        settings.useWideViewPort=true;settings.loadWithOverviewMode=true
        settings.userAgentString="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        settings.allowFileAccess=false;settings.allowContentAccess=false
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this,"JMethodNative",setOf("https://*.index-education.net")) { view, data, sourceOrigin, isMainFrame, _ ->
                if(isMainFrame && sourceOrigin.toString()==origin && PronotePolicy.isTrustedPage(view.url.orEmpty(),origin))Bridge().postMessage(data.data.orEmpty())
            }
        }
        webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean = request.url.scheme !in listOf("https","about")
            override fun onPageStarted(view:WebView,url:String?,favicon:android.graphics.Bitmap?){injected=false}
            override fun onPageFinished(view:WebView,url:String?) {
                if(!browserStarted||claimed||url==null||injected)return
                if(!PronotePolicy.isTrustedPage(url,origin)) {
                    // Redirection ENT/CAS hors de l'origine Pronote : les identifiants ne sont jamais saisis ailleurs que sur la page officielle.
                    if(credentialMode&&url.startsWith("https://"))revealForEnt()
                    return
                }
                inject(view)
            }
        }
    }
    private fun inject(view:WebView) {
        injected=true;automationStopped=false
        val config=JSONObject().put("nonce",nonce).put("pin",pin).put("allowedOrigin",origin).put("demo",demo)
        pendingCredentials?.let { (login,secret) -> config.put("credentials",JSONObject().put("username",login).put("password",secret)) }
        pendingCredentials=null;password=""
        val script=assets.open("PronoteAutomation.js").bufferedReader().use { it.readText() }
        view.evaluateJavascript("window.__JM_CONFIG=$config;\n$script",null)
    }
    private fun startWatchdog(millis:Long,text:String) {
        watchdog?.cancel();val attempt=generation
        watchdog=lifecycleScope.launch { delay(millis);if(attempt==generation&&!claimed)failure(text) }
    }
    private fun openDemo()=open("https://demo.index-education.net/pronote/eleve.html",true)
    private fun open(input:String,isDemo:Boolean,credentials:Pair<String,String>?=null) {
        val url=PronotePolicy.normalizedAddress(input)
        if(!isDemo)address=url
        try {
            val uri=PronotePolicy.validatedUrl(url);require(!uri.path.substringAfterLast('/').startsWith("mobile."))
            cancel(false);generation++;nonce=UUID.randomUUID().toString();pin=SecureRandom().nextInt(10000).toString().padStart(4,'0')
            origin="${uri.scheme}://${uri.authority}";demo=isDemo&&origin=="https://demo.index-education.net";claimed=false;injected=false;automationStopped=false
            pendingCredentials=credentials;credentialMode=credentials!=null;browserStarted=true
            if(credentialMode){showBrowser=false;busy=true;status="Connexion à Pronote";message="Ouverture de la page Pronote…"}
            else{showBrowser=true;busy=false;status="Connecte-toi à Pronote";message="La connexion et la vérification restent dans le site officiel. L’association sera ensuite automatique."}
            browser?.loadUrl(url)
            if(credentialMode)startWatchdog(90_000,"Pronote n’a pas répondu à temps. Vérifie l’adresse, puis réessaie ou termine la connexion dans la page affichée.")
            else startWatchdog(600_000,"La tentative de connexion a expiré. Relance-la pour continuer.")
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))failure("Mets à jour Android System WebView pour permettre l’association automatique sécurisée.")
        }catch(_:Exception){failure("Saisis l’adresse HTTPS de ton espace Élèves ou Parents hébergé sur index-education.net.")}
    }
    /** Affiche la page officielle pour que l'utilisateur termine lui-même (ENT, MFA, formulaire non reconnu). */
    private fun reveal(title:String,text:String,scriptStopped:Boolean) {
        pendingCredentials=null;password="";credentialMode=false
        busy=false;showBrowser=true;status=title;message=text;automationStopped=scriptStopped
        if(scriptStopped)watchdog?.cancel() else startWatchdog(600_000,"La tentative de connexion a expiré. Relance-la pour continuer.")
    }
    private fun revealForEnt()=reveal("Connexion par l’ENT","Ton établissement passe par un ENT : termine la connexion dans la page affichée.",false)
    /** Relance explicitement l'automatisation QR sur la page Pronote courante (après MFA, ENT ou erreur), avec un nouveau nonce et un nouveau code. */
    private fun resumeAutomation() {
        val view=browser?:return
        if(!browserStarted||!PronotePolicy.isTrustedPage(view.url.orEmpty(),origin)){message="Reviens sur la page Pronote de ton établissement, puis relance l’association.";return}
        view.evaluateJavascript("window.__JM_PRONOTE_POC?.stop()",null)
        generation++;activeJob?.cancel();activeJob=null
        nonce=UUID.randomUUID().toString();pin=SecureRandom().nextInt(10000).toString().padStart(4,'0');claimed=false;pendingCredentials=null;credentialMode=false
        status="Association automatique";message="Termine la connexion si besoin : l’association reprendra dès que Pronote sera ouvert."
        inject(view)
        startWatchdog(600_000,"La tentative de connexion a expiré. Relance-la pour continuer.")
    }
    inner class Bridge {
        fun postMessage(raw:String) {
            if(raw.length>2_800_000)return
            runOnUiThread {
                try {
                    val event=JSONObject(raw)
                    if(!browserStarted||claimed||event.optInt("version")!=1||event.optString("nonce")!=nonce||event.optString("origin")!=origin||!PronotePolicy.isTrustedPage(browser?.url.orEmpty(),origin)||!PronotePolicy.isTrustedPage(event.optString("url"),origin))return@runOnUiThread
                    when(event.optString("type")) {
                        "authenticated"->{showBrowser=false;busy=true;status="Association automatique";message="Préparation du QR code temporaire…";watchdog?.cancel();val attempt=generation;watchdog=lifecycleScope.launch{delay(45_000);if(attempt==generation&&!claimed)failure("L’association a pris trop de temps. Reprends la connexion dans Pronote.")}}
                        "progress"->{message=when(event.optString("stage")){"filling-login"->"Saisie de tes identifiants…";"submitting-login"->"Connexion à Pronote…";"opening-qr"->"Ouverture du QR code…";"filling-code"->"Création du code temporaire…";"generating-qr"->"Génération du QR code…";else->"Association en cours…"}}
                        "error"->when(event.optString("code")) {
                            "LOGIN_REJECTED"->if(credentialMode)rejected() else failure("Pronote a refusé l’identifiant ou le mot de passe. Vérifie-les puis réessaie.")
                            "MFA_REQUIRED"->reveal("Vérification demandée","Pronote demande une vérification supplémentaire : termine-la dans la page affichée.",true)
                            "LOGIN_FORM_NOT_FOUND"->reveal("Connexion à terminer","Le formulaire de connexion Pronote n’a pas été reconnu : connecte-toi directement dans la page affichée.",true)
                            "LOGIN_STALLED"->reveal("Connexion à terminer","Pronote ne répond pas après l’envoi de tes identifiants : vérifie la page affichée.",true)
                            else->failure("L’association automatique s’est arrêtée. Affiche Pronote, puis relance la connexion si nécessaire.")
                        }
                        "qr"->{if(event.optString("pin")!=pin)return@runOnUiThread;claimed=true;watchdog?.cancel();browser?.evaluateJavascript("window.__JM_PRONOTE_POC?.stop()",null);connectQr(event.getString("image"),pin)}
                    }
                }catch(_:Exception){failure("Impossible de lire le QR code. Relance la connexion.")}
            }
        }
    }
    private fun connectQr(image:String,code:String) {
        val attempt=generation;busy=true;status="Connexion native";message="Vérification du jeton et récupération des cours…"
        activeJob=lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    require(image.startsWith("data:image/png;base64,")&&image.length<2_800_000)
                    val bytes=Base64.decode(image.substringAfter(','),Base64.DEFAULT)
                    val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    require(options.outWidth in 1..4096&&options.outHeight in 1..4096)
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size);val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    val text=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text;bitmap.recycle()
                    val credentials=PronotePolicy.credentialsFromQr(JSONObject(text),code)
                    val connection=PronoteClient(PronoteTransport { method,url,body,headers -> if(attempt!=generation)throw CancellationException();NativeTransport().request(method,url,body,headers).also { if(attempt!=generation)throw CancellationException() } },credentials){synchronized(vault){if(attempt==generation)vault.save(it)}}
                    connection.login("qr");Triple(connection,connection.timetable(),connection.accountName)
                }
                if(attempt!=generation)return@launch
                client=result.first;lessons=result.second;account=result.third;children=result.first.children;selectedChild=result.first.selectedChildId.orEmpty();ready()
            }catch(_:CancellationException){}catch(error:Exception){if(attempt==generation)failure((error as? PronoteException)?.message?:"Connexion impossible. Retourne à Pronote pour réessayer.")}
        }
    }
    private fun nativeDemo() {
        cancel(false)
        browserStarted=false;showBrowser=false
        val attempt=generation
        busy=true;status="Test du protocole natif public";message="Connexion directe au compte public de démonstration, sans QR et sans enregistrer de jeton."
        activeJob=lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    val credentials=Credentials("https://demo.index-education.net/pronote/mobile.eleve.html?fd=1", "demonstration", "pronotevs", UUID.randomUUID().toString())
                    val guarded=PronoteTransport { method,url,body,headers ->
                        if(attempt!=generation)throw CancellationException()
                        NativeTransport().request(method,url,body,headers).also {if(attempt!=generation)throw CancellationException()}
                    }
                    val connection=PronoteClient(guarded,credentials)
                    connection.login("demo")
                    connection to connection.timetable()
                }
                if(attempt!=generation)return@launch
                client=result.first;lessons=result.second;account=result.first.accountName;children=result.first.children;selectedChild=result.first.selectedChildId.orEmpty()
                busy=false;status="Protocole natif public vérifié";message="Cours de démonstration lus directement en Kotlin. Ce test ne valide ni le QR ni la connexion de ton établissement. Aucun jeton enregistré."
            }catch(_:CancellationException){}catch(error:Exception){if(attempt==generation)failure((error as? PronoteException)?.message?:"Le test du protocole public a échoué (${error.javaClass.simpleName}). Aucun jeton n’a été enregistré.")}
        }
    }
    private fun restore() {
        val credentials=vault.load()?:return;val attempt=++generation;busy=true;status="Reconnexion native";message="Vérification de la session enregistrée…"
        activeJob=lifecycleScope.launch { try {val result=withContext(Dispatchers.IO){val connection=PronoteClient(PronoteTransport { method,url,body,headers -> if(attempt!=generation)throw CancellationException();NativeTransport().request(method,url,body,headers).also { if(attempt!=generation)throw CancellationException() } },credentials){synchronized(vault){if(attempt==generation)vault.save(it)}};connection.login();connection to connection.timetable()};if(attempt==generation){client=result.first;lessons=result.second;account=result.first.accountName;children=result.first.children;selectedChild=result.first.selectedChildId.orEmpty();ready()}}catch(_:CancellationException){}catch(error:Exception){if(attempt==generation)failure((error as? PronoteException)?.message?:"La session doit être reconnectée depuis Pronote.")} }
    }
    private fun refresh() {
        val connection=client?:return;if(busy)return;val attempt=generation;busy=true;status="Actualisation";message="Lecture de l’emploi du temps…"
        activeJob=lifecycleScope.launch {try{val result=withContext(Dispatchers.IO){connection.timetable(childId=selectedChild.takeIf{it.isNotBlank()})};if(attempt==generation){lessons=result;ready()}}catch(_:CancellationException){}catch(error:Exception){if(attempt==generation)failure((error as? PronoteException)?.message?:"Actualisation impossible. Reconnecte ton compte.")} }
    }
    private fun ready(){busy=false;showBrowser=false;status="Connexion vérifiée";message="Cours récupérés directement depuis Pronote. Le jeton reste chiffré sur ce téléphone."}
    private fun failure(text:String){watchdog?.cancel();pendingCredentials=null;credentialMode=false;automationStopped=browserStarted;busy=false;status="Connexion à reprendre";message=text;showBrowser=browserStarted;browser?.evaluateJavascript("window.__JM_PRONOTE_POC?.stop()",null)}
    /** Identifiants refusés : on arrête la tentative sans révéler la page et on rend le formulaire natif. */
    private fun rejected(){cancel(false);browserStarted=false;showBrowser=false;status="Identifiants refusés";message="Pronote a refusé l’identifiant ou le mot de passe. Vérifie-les puis réessaie."}
    private fun cancel(reset:Boolean=true){generation++;watchdog?.cancel();activeJob?.cancel();activeJob=null;busy=false;pendingCredentials=null;credentialMode=false;automationStopped=false;browser?.evaluateJavascript("window.__JM_PRONOTE_POC?.stop()",null);claimed=true;if(reset){browserStarted=false;showBrowser=false;status="Connexion annulée";message="Tu peux relancer une connexion quand tu veux."}}
    private fun forget(){cancel();client=null;account="";lessons=emptyList();children=emptyList();synchronized(vault){vault.clear()};android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.WebStorage.getInstance().deleteAllData();browser?.loadUrl("about:blank");status="Compte oublié";message="Le jeton et les données de connexion locales ont été supprimés."}
    override fun onDestroy(){cancel(false);browser?.let { if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))WebViewCompat.removeWebMessageListener(it,"JMethodNative") };browser?.destroy();super.onDestroy()}
}
