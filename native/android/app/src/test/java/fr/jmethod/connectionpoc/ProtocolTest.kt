package fr.jmethod.connectionpoc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun vectors()=JSONObject(javaClass.classLoader!!.getResourceAsStream("protocol-vectors.json")!!.bufferedReader().use {it.readText()})
    @Test fun aesMatchesPythonFixtures(){
        val aes=vectors().getJSONObject("aesCbcPkcs7");val key=PronoteCrypto.unhex(aes.getString("keyHex"));val iv=PronoteCrypto.unhex(aes.getString("ivHex"));val cases=aes.getJSONArray("cases")
        for(i in 0 until cases.length()){val row=cases.getJSONObject(i);val bytes=row.getString("plaintextUtf8").toByteArray();val encrypted=PronoteCrypto.encrypt(bytes,key,iv);assertEquals(row.getString("ciphertextHex"),PronoteCrypto.hex(encrypted));assertArrayEquals(bytes,PronoteCrypto.decrypt(encrypted,key,iv))}
    }
    @Test fun qrMatchesPythonFixtures(){val row=vectors().getJSONObject("qr");val credentials=PronotePolicy.credentialsFromQr(row.getJSONObject("qrData"),row.getString("pin"));assertEquals(row.getString("decodedUsernameUtf8"),credentials.username);assertEquals(row.getString("decodedPasswordUtf8"),credentials.password);assertEquals(row.getString("expectedMobileURL"),credentials.url)}
    @Test fun compressedUtf8RoundTrip(){val bytes="Géométrie été €".toByteArray();assertArrayEquals(bytes,PronoteCrypto.inflate(PronoteCrypto.deflate(bytes)))}
    @Test fun bridgeTrustIsExact(){assertTrue(PronotePolicy.isTrustedPage("https://demo.index-education.net/pronote/eleve.html","https://demo.index-education.net"));assertFalse(PronotePolicy.isTrustedPage("https://other.index-education.net/pronote/eleve.html","https://demo.index-education.net"));assertFalse(PronotePolicy.isTrustedPage("https://demo.index-education.net/pronote/mobile.eleve.html","https://demo.index-education.net"));assertFalse(PronotePolicy.isTrustedPage("https://demo.index-education.net.evil.test/pronote/eleve.html","https://demo.index-education.net"))}
    @Test fun addressIsNormalized(){
        assertEquals("https://school.index-education.net/pronote/eleve.html",PronotePolicy.normalizedAddress("  school.index-education.net "))
        assertEquals("https://school.index-education.net/pronote/eleve.html",PronotePolicy.normalizedAddress("http://school.index-education.net/pronote/"))
        assertEquals("https://school.index-education.net/pronote/eleve.html",PronotePolicy.normalizedAddress("school.index-education.net/pronote"))
        assertEquals("https://school.index-education.net/pronote/parent.html",PronotePolicy.normalizedAddress("https://school.index-education.net/pronote/mobile.parent.html?fd=1#x"))
        assertEquals("https://demo.index-education.net/pronote/eleve.html",PronotePolicy.normalizedAddress("https://demo.index-education.net/pronote/eleve.html"))
        assertEquals("https://demo.index-education.net/pronote/parent.html",PronotePolicy.normalizedAddress("HTTPS://Demo.Index-Education.net/pronote/parent.html?login=true"))
        assertEquals("",PronotePolicy.normalizedAddress("   "))
        PronotePolicy.validatedUrl(PronotePolicy.normalizedAddress("school.index-education.net"))
    }
    @Test fun endTimeFallsBackToGrid(){
        val grid=JSONArray();listOf("8h25","9h20","10h15","11h25","12h20").forEachIndexed { i,l -> grid.put(JSONObject().put("G",i).put("L",l)) }
        val general=JSONObject().put("ListeHeuresFin",JSONObject().put("_T",24).put("V",grid))
        assertEquals("2026-11-18T09:20",PronotePolicy.endFromGrid("2026-11-18T08:30",0,2,general))
        assertEquals("2026-11-18T08:25",PronotePolicy.endFromGrid("2026-11-18T07:30",0,1,general))
        assertEquals("",PronotePolicy.endFromGrid("2026-11-18T08:30",0,2,JSONObject()))
        assertEquals("",PronotePolicy.endFromGrid("2026-11-18T08:30",0,0,general))
    }
    @Test fun readOnlyWhitelist(){assertFalse(PronotePolicy.allowedFunctions.any {it.contains("Saisie")});assertTrue(PronotePolicy.allowedFunctions.contains("PageEmploiDuTemps"))}
    @Test fun parsesJavascriptWithoutEvaluation(){val value=PronotePolicy.parseStart("Start({h:123,a:3,CrA:true,CoA:false,http:false});");assertEquals(123,value.getInt("h"));assertTrue(value.getBoolean("CrA"))}
    @Test fun requestWireMatchesPythonAllModes(){
        val rows=vectors().getJSONArray("requests")
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i);val key=PronoteCrypto.unhex(row.getString("keyHex"));val iv=PronoteCrypto.unhex(row.getString("ivHex"))
            var bytes=row.getString("serializedDataUtf8").toByteArray()
            if(row.getBoolean("compress")){bytes=PronoteCrypto.deflate(PronoteCrypto.hex(bytes).toByteArray());assertEquals(row.getString("rawDeflateOfHexJsonHex"),PronoteCrypto.hex(bytes))}
            if(row.getBoolean("encrypt"))bytes=PronoteCrypto.encrypt(bytes,key,iv)
            if(row.getBoolean("encrypt")||row.getBoolean("compress"))assertEquals(row.getString("expectedDataSec"),PronoteCrypto.hex(bytes).uppercase())
            assertEquals(row.getString("expectedOrderHex"),PronoteCrypto.hex(PronoteCrypto.encrypt(row.getInt("requestNumber").toString().toByteArray(),key,iv)))
        }
    }
    @Test fun responseWireMatchesPythonAllModes(){
        val rows=vectors().getJSONArray("responses")
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i)
            if(!row.getBoolean("encrypt")&&!row.getBoolean("compress"))continue
            var bytes=PronoteCrypto.unhex(row.getString("wireDataSec"))
            if(row.getBoolean("encrypt"))bytes=PronoteCrypto.decrypt(bytes,PronoteCrypto.unhex(row.getString("keyHex")),PronoteCrypto.unhex(row.getString("ivHex")))
            if(row.getBoolean("compress"))bytes=PronoteCrypto.inflate(bytes)
            assertEquals(row.getString("serializedDataUtf8"),String(bytes))
        }
    }
}
