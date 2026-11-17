package fr.jmethod.connectionpoc

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PronoteException(val code: String, override val message: String) : Exception(message)
data class Credentials(val url: String, val username: String, val password: String, val uuid: String, val clientIdentifier: String? = null) {
    fun json() = JSONObject().put("url",url).put("username",username).put("password",password).put("uuid",uuid).put("clientIdentifier",clientIdentifier)
    companion object { fun fromJson(raw: JSONObject) = Credentials(raw.getString("url"), raw.getString("username"), raw.getString("password"), raw.getString("uuid"), raw.optString("clientIdentifier").takeIf { it.isNotBlank() && it != "null" }) }
}
data class Lesson(val id: String, val subject: String, val start: String, val end: String, val teacher: String, val room: String, val cancelled: Boolean)
data class Child(val id: String, val name: String)

object PronoteCrypto {
    val zeroIv = ByteArray(16)
    fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
    fun sha256(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8))).uppercase()
    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(text: String): ByteArray {
        if (text.length % 2 != 0 || !text.matches(Regex("[a-fA-F0-9]*"))) throw PronoteException("crypto", "Données de connexion invalides.")
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), IvParameterSpec(iv)); doFinal(data)
    }
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = try { Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), IvParameterSpec(iv)); doFinal(data)
    } } catch (_: Exception) { throw PronoteException("crypto", "Le QR code est expiré ou le code temporaire est incorrect.") }
    fun deflate(data: ByteArray): ByteArray {
        val compressor = Deflater(6,true); compressor.setInput(data); compressor.finish()
        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
        try { while (!compressor.finished()) { val count=compressor.deflate(buffer); out.write(buffer,0,count) } } finally { compressor.end() }
        return out.toByteArray()
    }
    fun inflate(data: ByteArray): ByteArray {
        val inflater=Inflater(true); inflater.setInput(data); val out=ByteArrayOutputStream(); val buffer=ByteArray(4096)
        try { while (!inflater.finished()) { val count=inflater.inflate(buffer); if(count==0 && (inflater.needsInput()||inflater.needsDictionary())) throw PronoteException("compression","Réponse Pronote incomplète."); out.write(buffer,0,count); if(out.size()>16*1024*1024)throw PronoteException("size","Réponse Pronote trop volumineuse.") } } finally { inflater.end() }
        return out.toByteArray()
    }
    fun rsa(data: ByteArray): ByteArray {
        val modulus=BigInteger("130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277")
        val key=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus,BigInteger.valueOf(65537)))
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE,key); doFinal(data) }
    }
}

object PronotePolicy {
    private val page = Regex(".*/(?:mobile\\.)?(eleve|parent)\\.html$")
    val allowedFunctions=setOf("FonctionParametres","Identification","Authentification","ParametresUtilisateur","PageInfosPerso","Navigation","PageEmploiDuTemps")
    fun validatedUrl(value: String): URI {
        val uri = try { URI(value) } catch (_:Exception) { throw PronoteException("url","Saisis une adresse Pronote HTTPS valide.") }
        val host=uri.host?.lowercase().orEmpty()
        if(uri.scheme!="https" || !host.endsWith(".index-education.net") || uri.userInfo!=null || (uri.port!=-1 && uri.port!=443) || !page.matches(uri.path.orEmpty())) throw PronoteException("url","Ce POC accepte les espaces Élèves et Parents hébergés sur index-education.net.")
        return uri
    }
    /** Accepte une saisie approximative (sans https, sans page, avec ?/#, page mobile) et la ramène à l’espace web officiel. */
    fun normalizedAddress(input: String, defaultPage: String = "eleve.html"): String {
        var text=input.trim(); if(text.isEmpty()) return text
        val lower=text.lowercase()
        text=when { lower.startsWith("https://")->"https://"+text.substring(8); lower.startsWith("http://")->"https://"+text.substring(7); else->"https://$text" }
        text=text.substringBefore('#').substringBefore('?')
        val uri=try { URI(text) } catch (_:Exception) { return text }
        val authority=uri.rawAuthority?.lowercase() ?: return text
        var path=uri.rawPath.orEmpty()
        val last=path.substringAfterLast('/').lowercase()
        if(last.endsWith(".html")) { if(last.startsWith("mobile.")) path=path.substringBeforeLast('/')+"/"+last.removePrefix("mobile.") }
        else { if(!path.lowercase().contains("/pronote")) path="/pronote"; path=if(path.endsWith("/")) path+defaultPage else "$path/$defaultPage" }
        return "https://$authority$path"
    }
    fun isTrustedPage(value: String, allowedOrigin: String): Boolean = try {
        val uri=validatedUrl(value); uri.scheme+"://"+uri.authority == allowedOrigin && !uri.path.substringAfterLast('/').startsWith("mobile.")
    } catch (_:Exception) { false }
    fun parseStart(html: String): JSONObject {
        val body=Regex("\\bStart\\s*\\(\\s*(\\{[^}]*\\})\\s*\\)").find(html)?.groupValues?.get(1) ?: throw PronoteException("html","La page Pronote nécessite une connexion officielle ou son format a changé.")
        val result=JSONObject()
        val attributes=Regex("(?:\\{|,)\\s*['\"]?([\\w]+)['\"]?\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([^,}]+))")
        attributes.findAll(body).forEach { match ->
            val raw = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
            val value:Any = when { raw.equals("true",true)->true; raw.equals("false",true)->false; raw.toLongOrNull()!=null->raw.toLong(); else->raw }
            result.put(match.groupValues[1],value)
        }
        if(!result.has("h")||!result.has("a"))throw PronoteException("html","Paramètres de session Pronote introuvables.")
        return result
    }
    fun credentialsFromQr(qr:JSONObject,pin:String):Credentials {
        val uri=validatedUrl(qr.getString("url"))
        if(!pin.matches(Regex("[0-9]{4}")))throw PronoteException("qr","Le code temporaire doit contenir quatre chiffres.")
        val key=PronoteCrypto.md5(pin.toByteArray(UTF_8))
        val login=String(PronoteCrypto.decrypt(PronoteCrypto.unhex(qr.getString("login")),key,PronoteCrypto.zeroIv),UTF_8)
        val token=String(PronoteCrypto.decrypt(PronoteCrypto.unhex(qr.getString("jeton")),key,PronoteCrypto.zeroIv),UTF_8)
        val last=uri.path.substringAfterLast('/').let { if(it.startsWith("mobile."))it else "mobile.$it" }
        val path=uri.path.substringBeforeLast('/')+"/"+last
        val url=URI("https",uri.authority,path,"fd=1&bydlg=A6ABB224-12DD-4E31-AD3E-8A39A1C2C335&login=true",null).toString()
        return Credentials(url,login,token,UUID.randomUUID().toString())
    }
    /** Heure de fin déduite de la grille `General.ListeHeuresFin` quand `DateDuCoursFin` est absent (cf. Util.place2time). */
    fun endFromGrid(start: String, place: Int, duration: Int, general: JSONObject?): String {
        val grid=general?.opt("ListeHeuresFin").let { (it as? JSONObject)?.optJSONArray("V") ?: it as? JSONArray } ?: return ""
        val count=grid.length(); if(count<2 || start.length<10 || duration<1) return ""
        var endPlace=place%(count-1)+duration-1
        if(endPlace>count) endPlace%=count-1
        val label=(0 until count).map { grid.getJSONObject(it) }.firstOrNull { it.optInt("G",-1)==endPlace }?.optString("L").orEmpty()
        val match=Regex("^(\\d{1,2})h(\\d{2})$").find(label.trim()) ?: return ""
        return start.take(10)+"T"+match.groupValues[1].padStart(2,'0')+":"+match.groupValues[2]
    }
    fun date(raw: Any?): String {
        val value=if(raw is JSONObject)raw.optString("V")else raw?.toString().orEmpty()
        return try { LocalDateTime.parse(value,DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")).toString() } catch (_:Exception) {
            try { LocalDate.parse(value,DateTimeFormatter.ofPattern("dd/MM/yyyy")).atStartOfDay().toString() }catch (_:Exception){""}
        }
    }
}

data class HttpResult(val status:Int,val headers:Map<String,List<String>>,val body:String)
fun interface PronoteTransport { fun request(method:String,url:String,body:String?,headers:Map<String,String>):HttpResult }
class NativeTransport : PronoteTransport {
    override fun request(method:String,url:String,body:String?,headers:Map<String,String>):HttpResult {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects=false;connection.requestMethod=method;connection.connectTimeout=15_000;connection.readTimeout=30_000
        headers.forEach { (name,value)->connection.setRequestProperty(name,value) }
        return try {
            if(body!=null){connection.doOutput=true;connection.outputStream.use { it.write(body.toByteArray(UTF_8)) }}
            val status=connection.responseCode
            val text=(if(status in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader(UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status,connection.headerFields.filterKeys { it!=null },text)
        }finally{connection.disconnect()}
    }
}

class PronoteClient(private val transport:PronoteTransport,var credentials:Credentials,private val save:(Credentials)->Unit={}) {
    private var attributes=JSONObject();private var parameters=JSONObject();private var user=JSONObject()
    private var key=PronoteCrypto.md5(byteArrayOf());private var iv=PronoteCrypto.zeroIv;private var order=1
    private var compressed=false;private var encrypted=false;private val cookies=linkedMapOf<String,String>()
    private val origin=PronotePolicy.validatedUrl(credentials.url).let { it.scheme+"://"+it.authority }
    private var root=credentials.url.substringBeforeLast('/')
    var accountName="";private set
    var serverVersion="";private set
    var children:List<Child> = emptyList();private set
    var selectedChildId:String?=null;private set
    private fun request(method:String,url:String,body:String?=null):HttpResult {
        if(!url.startsWith("$origin/"))throw PronoteException("origin","Requête hors établissement refusée.")
        val headers=linkedMapOf("User-Agent" to "iPhone","Accept" to if(method=="GET")"text/html" else "application/json")
        if(method=="POST")headers["Content-Type"]="application/json"
        if(cookies.isNotEmpty())headers["Cookie"]=cookies.entries.joinToString("; "){it.key+"="+it.value}
        val response=transport.request(method,url,body,headers)
        response.headers.filterKeys { it.equals("Set-Cookie",true) }.values.flatten().forEach { cookie ->
            val pair=cookie.substringBefore(';');val equal=pair.indexOf('=');if(equal>0)cookies[pair.substring(0,equal).trim()]=pair.substring(equal+1)
        }
        if(response.status !in 200..299)throw PronoteException("http","Pronote répond avec le statut ${response.status}. Réouvre la page officielle si nécessaire.")
        return response
    }
    fun call(function:String,data:JSONObject?=null,tab:Int?=null,nextIv:ByteArray?=null):JSONObject {
        if(function !in PronotePolicy.allowedFunctions)throw PronoteException("readonly","Cette opération n’est pas autorisée en lecture seule.")
        val payload=JSONObject()
        if(tab!=null){val signature=JSONObject().put("onglet",tab);if(children.isNotEmpty())signature.put("membre",JSONObject().put("N",user.get("N")).put("G",4));payload.put("Signature",signature)}
        if(data!=null)payload.put("data",data)
        var secured:Any=payload
        if(compressed)secured=PronoteCrypto.hex(PronoteCrypto.deflate(PronoteCrypto.hex(payload.toString().toByteArray(UTF_8)).toByteArray(UTF_8))).uppercase()
        if(encrypted)secured=PronoteCrypto.hex(PronoteCrypto.encrypt(if(secured is String)PronoteCrypto.unhex(secured)else payload.toString().toByteArray(UTF_8),key,iv)).uppercase()
        val number=PronoteCrypto.hex(PronoteCrypto.encrypt(order.toString().toByteArray(UTF_8),key,iv))
        val envelope=JSONObject().put("session",attributes.getLong("h")).put("no",number).put("id",function).put("dataSec",secured)
        val response=JSONObject(request("POST","$root/appelfonction/${attributes.getInt("a")}/${attributes.getLong("h")}/$number",envelope.toString()).body)
        order+=2
        if(response.has("Erreur"))throw PronoteException("pronote-${response.getJSONObject("Erreur").optInt("G")}","Pronote a refusé la requête. Réouvre la connexion sans multiplier les essais.")
        if(nextIv!=null)iv=nextIv
        val raw=response.get("dataSec")
        val decoded=when{
            encrypted && compressed->String(PronoteCrypto.inflate(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String),key,iv)),UTF_8)
            encrypted->String(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String),key,iv),UTF_8)
            compressed->String(PronoteCrypto.inflate(PronoteCrypto.unhex(raw as String)),UTF_8)
            else->raw.toString()
        }
        val section=JSONObject(decoded)
        return section.optJSONObject("data")?:section
    }
    fun login(mode:String="token") {
        order=1;key=PronoteCrypto.md5(byteArrayOf());iv=PronoteCrypto.zeroIv
        PronotePolicy.validatedUrl(credentials.url)
        attributes=PronotePolicy.parseStart(request("GET",credentials.url).body)
        compressed=attributes.optBoolean("CoA",false);encrypted=attributes.optBoolean("CrA",false)
        val temporary=ByteArray(16).also { SecureRandom().nextBytes(it) }
        val uuidData=if(attributes.optBoolean("http",false))PronoteCrypto.rsa(temporary)else temporary
        parameters=call("FonctionParametres",JSONObject().put("Uuid",Base64.getEncoder().encodeToString(uuidData)).put("identifiantNav",credentials.clientIdentifier?:JSONObject.NULL),nextIv=PronoteCrypto.md5(temporary))
        credentials=credentials.copy(clientIdentifier=parameters.optString("identifiantNav").takeIf { it.isNotBlank() }?:credentials.clientIdentifier)
        serverVersion=parameters.optJSONObject("General")?.opt("versionPN")?.toString().orEmpty()
        val identification=call("Identification",JSONObject().put("genreConnexion",0).put("genreEspace",attributes.getInt("a")).put("identifiant",credentials.username).put("pourENT",false).put("enConnexionAuto",false).put("demandeConnexionAuto",false).put("demandeConnexionAppliMobile",mode=="qr").put("demandeConnexionAppliMobileJeton",mode=="qr").put("enConnexionAppliMobile",mode=="token").put("uuidAppliMobile",if(mode=="demo")"" else credentials.uuid).put("loginTokenSAV",""))
        val username=if(identification.optBoolean("modeCompLog"))credentials.username.lowercase()else credentials.username
        val password=if(identification.optBoolean("modeCompMdp"))credentials.password.lowercase()else credentials.password
        val authKey=PronoteCrypto.md5((username+PronoteCrypto.sha256(identification.optString("alea")+password)).toByteArray(UTF_8))
        val challenge=PronoteCrypto.hex(PronoteCrypto.encrypt(identification.getString("challenge").toByteArray(UTF_8),authKey,iv))
        val auth=call("Authentification",JSONObject().put("connexion",0).put("challenge",challenge).put("espace",attributes.getInt("a")))
        if(!auth.has("cle"))throw PronoteException("login","La connexion n’a pas été acceptée.")
        val secret=String(PronoteCrypto.decrypt(PronoteCrypto.unhex(auth.getString("cle")),authKey,iv),UTF_8)
        key=PronoteCrypto.md5(secret.split(',').map { it.toInt().toByte() }.toByteArray())
        auth.optString("jetonConnexionAppliMobile").takeIf { it.isNotBlank() }?.let { credentials=credentials.copy(password=it) }
        if(mode!="demo")save(credentials)
        val actions=auth.optJSONObject("actionsDoubleAuth")?.optString("V")?.let { runCatching { JSONArray(it) }.getOrNull() }
        if(actions!=null && (0 until actions.length()).any { actions.optInt(it) in setOf(3,5) })throw PronoteException("mfa","Une validation de sécurité supplémentaire est nécessaire dans Pronote.")
        
        val personal=call("ParametresUtilisateur");user=personal.getJSONObject("ressource");accountName=user.optString("L","Compte connecté")
        val childResources=user.optJSONArray("listeRessources")?:user.optJSONObject("listeRessources")?.optJSONArray("V")
        children=if(childResources==null)emptyList() else (0 until childResources.length()).map { val child=childResources.getJSONObject(it);Child(child.get("N").toString(),child.optString("L")) }
        if(childResources!=null && childResources.length()>0){user=childResources.getJSONObject(0);selectedChildId=user.get("N").toString()}
        if(mode=="qr"){
            val security=call("PageInfosPerso",tab=49).optJSONObject("securisation")?.optInt("mode",0)?:0
            // Compte sécurisé : Pronote exige une seconde connexion avec le jeton mobile qui vient d'être renouvelé (comme pronotepy et le client iOS).
            if(security!=0){login("token");return}
        }
        if(mode!="demo")save(credentials)
    }
    fun timetable(days:Int=7,childId:String?=null):List<Lesson> {
        if(childId!=null && children.isNotEmpty()){
            val parent=call("ParametresUtilisateur").getJSONObject("ressource")
            val resources=parent.optJSONArray("listeRessources")?:parent.optJSONObject("listeRessources")?.optJSONArray("V")
            if(resources!=null){user=(0 until resources.length()).map { resources.getJSONObject(it) }.firstOrNull { it.get("N").toString()==childId }?:throw PronoteException("child","Enfant introuvable.");selectedChildId=childId}
        }
        val today=LocalDate.now();val last=today.plusDays(days.coerceIn(1,14).toLong()-1)
        val monday=PronotePolicy.date(parameters.getJSONObject("General").get("PremierLundi")).take(10).let { LocalDate.parse(it) }
        fun week(day:LocalDate)=1+(ChronoUnit.DAYS.between(monday,day)/7).toInt()
        val results=mutableListOf<Lesson>()
        for(w in week(today)..week(last)){
            val data=JSONObject().put("ressource",user).put("Ressource",user).put("avecAbsencesEleve",false).put("avecConseilDeClasse",true).put("estEDTPermanence",false).put("avecAbsencesRessource",true).put("avecDisponibilites",true).put("avecInfosPrefsGrille",true).put("NumeroSemaine",w).put("numeroSemaine",w)
            val lessons=call("PageEmploiDuTemps",data,16).optJSONArray("ListeCours")?:JSONArray()
            for(i in 0 until lessons.length()){
                val lesson=lessons.getJSONObject(i);val start=PronotePolicy.date(lesson.opt("DateDuCours"));if(start.isEmpty())continue
                val date=LocalDate.parse(start.take(10));if(date<today||date>last)continue
                val raw=lesson.optJSONObject("ListeContenus")?.optJSONArray("V")?:JSONArray()
                val content=(0 until raw.length()).map { raw.getJSONObject(it) }
                fun labels(kind:Int)=content.filter { it.optInt("G")==kind }.joinToString(", "){it.optString("L")}
                results+=Lesson("${lesson.optString("N")}-$start-${lesson.optInt("P")}",labels(16).ifBlank { "Cours" },start,PronotePolicy.date(lesson.opt("DateDuCoursFin")).ifEmpty { PronotePolicy.endFromGrid(start,lesson.optInt("place"),lesson.optInt("duree"),parameters.optJSONObject("General")) },labels(3),labels(17),lesson.optBoolean("estAnnule"))
            }
        }
        return results.sortedBy { it.start }
    }
}
