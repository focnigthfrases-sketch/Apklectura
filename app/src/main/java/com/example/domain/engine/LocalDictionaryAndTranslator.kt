package com.example.domain.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.Normalizer

data class DictionaryEntry(
  val word: String,
  val partOfSpeech: String,
  val definition: String,
  val synonyms: List<String>,
  val englishTranslation: String,
  val isExactMatch: Boolean
)

data class TranslationResult(
  val sourceText: String,
  val translatedText: String,
  val targetLanguageLabel: String,
  val coverageNote: String
)

/**
 * Offline Spanish/English literary dictionary and phrase translator with zero server dependency.
 */
object LocalDictionaryAndTranslator {

  private data class LexEntry(
    val pos: String,
    val def: String,
    val syn: List<String>,
    val en: String
  )

  private val lexicon: Map<String, LexEntry> = mapOf(
    "hidalgo" to LexEntry("sustantivo", "Persona que por linaje o nobleza pertenece a una clase distinguida.", listOf("noble", "caballero", "señor"), "nobleman / gentleman"),
    "adarga" to LexEntry("sustantivo", "Escudo de cuero ovalado o en forma de corazón usado antiguamente.", listOf("escudo", "rodela", "broquel"), "leather shield"),
    "rocin" to LexEntry("sustantivo", "Caballo de poca alzada, flaco o de escaso valor para la guerra.", listOf("jamugo", "caballejo", "corcel"), "nag / workhorse"),
    "astillero" to LexEntry("sustantivo", "Percha o armazón donde se colocaban las lanzas y picas.", listOf("armero", "soporte"), "lance rack"),
    "ingenio" to LexEntry("sustantivo", "Facultad humana para discurrir, inventar o crear con agudeza.", listOf("talento", "agudeza", "inventiva"), "wit / ingenuity"),
    "sosiego" to LexEntry("sustantivo", "Estado de calma, quietud y tranquilidad del ánimo.", listOf("calma", "serenidad", "paz"), "calm / serenity"),
    "apacible" to LexEntry("adjetivo", "Agradable, sereno, manso y libre de agitación.", listOf("sereno", "suave", "tranquilo"), "peaceful / gentle"),
    "amenidad" to LexEntry("sustantivo", "Cualidad de lo que resulta grato, variado y entretenido.", listOf("encanto", "agrado", "dulzura"), "pleasantness"),
    "quietud" to LexEntry("sustantivo", "Falta de movimiento o alteración; paz interior.", listOf("reposo", "calma", "silencio"), "stillness / quiet"),
    "fecundo" to LexEntry("adjetivo", "Que produce abundantes frutos, ideas u obras.", listOf("fértil", "productivo", "prolífico"), "fertile / fruitful"),
    "esteril" to LexEntry("adjetivo", "Que no da fruto ni produce resultados.", listOf("infecundo", "árido", "improductivo"), "barren / sterile"),
    "fantasia" to LexEntry("sustantivo", "Facultad de la mente para imaginar sucesos o imágenes ideales.", listOf("imaginación", "ilusión", "ensueño"), "imagination / fantasy"),
    "encantamento" to LexEntry("sustantivo", "Hechizo o prodigio atribuido a artes mágicas.", listOf("hechizo", "sortilegio", "embrujo"), "enchantment"),
    "aventura" to LexEntry("sustantivo", "Suceso extraño, arriesgado o memorable que emprende alguien.", listOf("hazaña", "lance", "empresa"), "adventure"),
    "escudero" to LexEntry("sustantivo", "Sirviente o acompañante que llevaba el escudo del caballero.", listOf("asistente", "ayudante"), "squire"),
    "molino" to LexEntry("sustantivo", "Máquina movida por viento o agua para moler grano.", listOf("aceña", "tahona"), "mill"),
    "aspa" to LexEntry("sustantivo", "Cada una de las piezas cruzadas que giran impulsadas por el viento.", listOf("pala", "hélice"), "windmill blade"),
    "lanza" to LexEntry("sustantivo", "Arma ofensiva de asta larga con punta aguda de hierro.", listOf("pica", "jabalina"), "lance / spear"),
    "batalla" to LexEntry("sustantivo", "Combate o enfrentamiento entre dos bandos.", listOf("combate", "contienda", "lucha"), "battle"),
    "despojo" to LexEntry("sustantivo", "Botín o bienes arrebatados tras una victoria.", listOf("botín", "presa"), "spoils"),
    "caballero" to LexEntry("sustantivo", "Hombre noble que sigue ideales de honor, valor y cortesía.", listOf("hidalgo", "paladín"), "knight / gentleman"),
    "juicio" to LexEntry("sustantivo", "Facultad del entendimiento para discernir lo verdadero de lo falso.", listOf("razón", "cordura", "seso"), "judgment / sanity"),
    "celebro" to LexEntry("sustantivo", "Forma clásica de cerebro; sede del pensamiento y la razón.", listOf("cerebro", "mente", "entendimiento"), "brain / mind"),
    "melancolia" to LexEntry("sustantivo", "Tristeza vaga, profunda y sosegada del ánimo.", listOf("nostalgia", "pesar", "añoranza"), "melancholy"),
    "lira" to LexEntry("sustantivo", "Instrumento musical de cuerdas; símbolo clásico de la poesía.", listOf("arpa", "poesía", "canto"), "lyre"),
    "pupila" to LexEntry("sustantivo", "Abertura central del iris del ojo por donde entra la luz.", listOf("niña del ojo", "mirada"), "pupil (of the eye)"),
    "suspiro" to LexEntry("sustantivo", "Aspiración y espiración prolongada que expresa emoción o anhelo.", listOf("lamento", "aliento"), "sigh"),
    "golondrina" to LexEntry("sustantivo", "Ave migratoria de alas largas y cola ahorquillada.", listOf("ave", "pájaro"), "swallow (bird)"),
    "madreselva" to LexEntry("sustantivo", "Planta trepadora de flores aromáticas.", listOf("enredadera"), "honeysuckle"),
    "rocio" to LexEntry("sustantivo", "Humedad condensada en gotas sobre las plantas durante la noche.", listOf("sereno", "escarcha"), "dew"),
    "pergamino" to LexEntry("sustantivo", "Piel tratada de animal usada antiguamente para escribir libros.", listOf("códice", "manuscrito"), "parchment"),
    "trovador" to LexEntry("sustantivo", "Poeta y músico medieval que componía versos cortesanos.", listOf("bardo", "juglar", "poeta"), "troubadour"),
    "ensueno" to LexEntry("sustantivo", "Sueño, ilusión o fantasía idealizada mientras se está despierto.", listOf("quimera", "ilusión", "sueño"), "daydream / reverie"),
    "soledad" to LexEntry("sustantivo", "Carencia de compañía o retiro voluntario para la contemplación.", listOf("aislamiento", "retiro"), "solitude"),
    "hermosura" to LexEntry("sustantivo", "Belleza notable de una persona, paisaje u obra.", listOf("belleza", "lindeza"), "beauty"),
    "emula" to LexEntry("sustantivo", "Rival o imitadora que compite en mérito con algo.", listOf("rival", "semejante"), "rival / emulator"),
    "deposito" to LexEntry("sustantivo", "Lugar o archivo donde se guarda y conserva algo valioso.", listOf("archivo", "reserva"), "repository"),
    "cortesia" to LexEntry("sustantivo", "Demostración de respeto, atención y buenos modales.", listOf("amabilidad", "urbanidad"), "courtesy"),
    "ventura" to LexEntry("sustantivo", "Suerte favorable, dicha o destino incierto.", listOf("fortuna", "suerte", "dicha"), "fortune / luck"),
    "trance" to LexEntry("sustantivo", "Momento crítico, decisivo o apurado de una situación.", listOf("apuro", "lance", "instante"), "critical moment"),
    "libro" to LexEntry("sustantivo", "Conjunto de hojas impresas o digitales encuadernadas en volumen.", listOf("volumen", "obra", "ejemplar"), "book"),
    "lectura" to LexEntry("sustantivo", "Acción de leer e interpretar un texto escrito.", listOf("estudio", "revisión"), "reading"),
    "capitulo" to LexEntry("sustantivo", "Cada una de las divisiones principales de un libro.", listOf("sección", "apartado"), "chapter"),
    "historia" to LexEntry("sustantivo", "Narración de hechos reales o ficticios a lo largo del tiempo.", listOf("relato", "crónica"), "history / story"),
    "poesia" to LexEntry("sustantivo", "Expresión artística de la belleza mediante el verso o la prosa lírica.", listOf("lírica", "verso"), "poetry"),
    "verdad" to LexEntry("sustantivo", "Conformidad entre lo que se dice o piensa y la realidad.", listOf("certeza", "realidad"), "truth"),
    "tiempo" to LexEntry("sustantivo", "Magnitud física que ordena la secuencia de los acontecimientos.", listOf("época", "duración"), "time"),
    "mundo" to LexEntry("sustantivo", "Conjunto de todas las cosas creadas o la sociedad humana.", listOf("universo", "tierra"), "world"),
    "corazon" to LexEntry("sustantivo", "Órgano vital y símbolo tradicional del afecto y el ánimo.", listOf("ánimo", "alma"), "heart"),
    "alma" to LexEntry("sustantivo", "Principio espiritual e íntimo del ser humano.", listOf("espíritu", "ánimo"), "soul"),
    "agua" to LexEntry("sustantivo", "Sustancia líquida esencial para la vida.", listOf("líquido"), "water"),
    "aire" to LexEntry("sustantivo", "Mezcla gaseosa que forma la atmósfera terrestre.", listOf("viento", "brisa"), "air"),
    "luz" to LexEntry("sustantivo", "Radiación que hace visibles los objetos.", listOf("claridad", "resplandor"), "light"),
    "sombra" to LexEntry("sustantivo", "Oscuridad proyectada al interceptar la luz.", listOf("penumbra", "silueta"), "shadow"),
    "noche" to LexEntry("sustantivo", "Periodo comprendido entre el ocaso y el amanecer.", listOf("oscuridad", "velada"), "night"),
    "primavera" to LexEntry("sustantivo", "Estación templada del año en que florecen las plantas.", listOf("renacimiento"), "spring"),
    "silencio" to LexEntry("sustantivo", "Ausencia total o notable de ruido y palabras.", listOf("calma", "quietud"), "silence"),
    "palabras" to LexEntry("sustantivo", "Unidades del lenguaje dotadas de significado.", listOf("voces", "términos"), "words"),
    "palabra" to LexEntry("sustantivo", "Unidad lingüística que expresa una idea o concepto.", listOf("término", "vocablo"), "word")
  )

  private val phraseTranslationsEsToEn: Map<String, String> = mapOf(
    "en un lugar de la mancha" to "In a village of La Mancha",
    "de cuyo nombre no quiero acordarme" to "the name of which I have no desire to call to mind",
    "molinos de viento" to "windmills",
    "no son gigantes" to "they are not giants",
    "habra poesia" to "there will be poetry",
    "volveran las oscuras golondrinas" to "the dark swallows will return",
    "poesia eres tu" to "poetry... is you",
    "desocupado lector" to "idle reader"
  )

  private val wordEsToEn: Map<String, String> = mapOf(
    "el" to "the", "la" to "the", "los" to "the", "las" to "the", "un" to "a", "una" to "a",
    "de" to "of", "del" to "of the", "en" to "in", "y" to "and", "o" to "or", "con" to "with",
    "por" to "by", "para" to "for", "sin" to "without", "sobre" to "on", "entre" to "between",
    "que" to "that", "no" to "not", "si" to "yes", "pero" to "but", "como" to "like",
    "cuando" to "when", "donde" to "where", "todo" to "all", "siempre" to "always", "nunca" to "never",
    "muy" to "very", "mas" to "more", "este" to "this", "esta" to "this", "ese" to "that",
    "su" to "his/her", "mi" to "my", "tu" to "your", "yo" to "I", "tu" to "you",
    "era" to "was", "es" to "is", "son" to "are", "fue" to "was", "hay" to "there is",
    "lugar" to "place", "nombre" to "name", "tiempo" to "time", "vida" to "life", "amor" to "love",
    "ojos" to "eyes", "mano" to "hand", "campo" to "field", "cielo" to "sky", "mar" to "sea",
    "sol" to "sun", "luna" to "moon", "flor" to "flower", "flores" to "flowers", "dia" to "day",
    "dias" to "days", "noches" to "nights", "casa" to "house", "camino" to "road", "amigo" to "friend",
    "senor" to "sir", "senora" to "lady", "lector" to "reader", "historia" to "story",
    "grande" to "great", "pequeno" to "small", "bueno" to "good", "malo" to "bad",
    "nuevo" to "new", "antiguo" to "ancient", "oscuro" to "dark", "oscuras" to "dark",
    "claro" to "clear", "blanco" to "white", "negro" to "black", "azul" to "blue",
    "leer" to "to read", "escribir" to "to write", "vivir" to "to live", "amar" to "to love",
    "mirar" to "to look", "soñar" to "to dream", "sonar" to "to sound", "volver" to "to return",
    "pensar" to "to think", "saber" to "to know", "querer" to "to want", "poder" to "to be able",
    "gigantes" to "giants", "viento" to "wind", "caballo" to "horse", "espada" to "sword",
    "nidos" to "nests", "balcon" to "balcony", "lagrimas" to "tears", "sueno" to "dream"
  )

  fun normalize(word: String): String {
    val clean = word.trim().lowercase().replace(Regex("""[^\p{L}]"""), "")
    val decomposed = Normalizer.normalize(clean, Normalizer.Form.NFD)
    return decomposed.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
  }

  fun lookupWord(rawWord: String): DictionaryEntry {
    val display = rawWord.trim().trim { !it.isLetter() }
    val norm = normalize(display)

    val direct = lexicon[norm]
    if (direct != null) {
      return DictionaryEntry(
        word = display.ifBlank { norm },
        partOfSpeech = direct.pos,
        definition = direct.def,
        synonyms = direct.syn,
        englishTranslation = direct.en,
        isExactMatch = true
      )
    }

    // Simple morphological stem fallback (plural -s / -es)
    val stemmed = when {
      norm.endsWith("es") && norm.length > 4 -> norm.dropLast(2)
      norm.endsWith("s") && norm.length > 3 -> norm.dropLast(1)
      else -> norm
    }
    val stemHit = lexicon[stemmed]
    if (stemHit != null) {
      return DictionaryEntry(
        word = display.ifBlank { norm },
        partOfSpeech = stemHit.pos,
        definition = stemHit.def,
        synonyms = stemHit.syn,
        englishTranslation = stemHit.en,
        isExactMatch = true
      )
    }

    // Fallback English gloss if known
    val enGloss = wordEsToEn[norm] ?: wordEsToEn[stemmed]
    return if (enGloss != null) {
      DictionaryEntry(
        word = display.ifBlank { norm },
        partOfSpeech = "léxico general",
        definition = "Término frecuente en textos literarios (equivalente en inglés: «$enGloss»).",
        synonyms = emptyList(),
        englishTranslation = enGloss,
        isExactMatch = false
      )
    } else {
      DictionaryEntry(
        word = display.ifBlank { rawWord },
        partOfSpeech = "consulta",
        definition = "No está en el diccionario local integrado. Puedes guardarla en tu Vocabulario o abrir la definición externa en línea.",
        synonyms = emptyList(),
        englishTranslation = "",
        isExactMatch = false
      )
    }
  }

  fun translateLocally(text: String, targetLang: String = "EN"): TranslationResult {
    val trimmed = text.trim()
    val normalizedFull = normalizePhrase(trimmed)

    // Check known literary phrases first
    for ((esPhrase, enPhrase) in phraseTranslationsEsToEn) {
      if (normalizedFull.contains(esPhrase)) {
        return TranslationResult(
          sourceText = trimmed,
          translatedText = enPhrase,
          targetLanguageLabel = "Inglés (traducción local)",
          coverageNote = "Traducción local instantánea sin conexión."
        )
      }
    }

    val words = Regex("""[\p{L}]+|[^\p{L}]+""").findAll(trimmed).map { it.value }.toList()
    var knownCount = 0
    var totalWords = 0

    val translated = buildString {
      for (token in words) {
        if (token.any { it.isLetter() }) {
          totalWords++
          val norm = normalize(token)
          val hit = lexicon[norm]?.en?.substringBefore(" /") ?: wordEsToEn[norm]
          if (hit != null) {
            knownCount++
            val cased = if (token.first().isUpperCase()) hit.replaceFirstChar { it.uppercase() } else hit
            append(cased)
          } else {
            append(token)
          }
        } else {
          append(token)
        }
      }
    }

    val pct = if (totalWords > 0) (knownCount * 100) / totalWords else 0
    val note = if (pct >= 50) {
      "Traducción aproximada sin conexión ($pct% del léxico reconocido). Usa «Traducir en navegador» para frases complejas."
    } else {
      "Traducción parcial sin conexión ($pct% reconocido). Pulsa «Abrir traductor completo» si tienes conexión."
    }

    return TranslationResult(
      sourceText = trimmed,
      translatedText = translated,
      targetLanguageLabel = if (targetLang == "EN") "Inglés" else targetLang,
      coverageNote = note
    )
  }

  fun extractNotableWords(fragment: String, maxWords: Int = 14): List<String> {
    return Regex("""[\p{L}]{4,}""")
      .findAll(fragment)
      .map { it.value }
      .distinctBy { normalize(it) }
      .sortedByDescending { if (lexicon.containsKey(normalize(it))) 100 + it.length else it.length }
      .take(maxWords)
      .toList()
  }

  fun openExternalDictionary(context: Context, word: String) {
    val encoded = Uri.encode(word.trim())
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dle.rae.es/$encoded"))
    runCatching { context.startActivity(intent) }
  }

  fun openExternalTranslator(context: Context, text: String) {
    val encoded = Uri.encode(text.trim().take(500))
    val intent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=$encoded&op=translate")
    )
    runCatching { context.startActivity(intent) }
  }

  private fun normalizePhrase(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
    return decomposed.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
      .replace(Regex("""[^\p{L}\s]"""), " ")
      .replace(Regex("""\s+"""), " ")
      .trim()
  }
}
