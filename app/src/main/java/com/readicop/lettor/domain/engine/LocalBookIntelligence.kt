package com.readicop.lettor.domain.engine

import java.text.Normalizer
import kotlin.math.ln

data class ChapterSummaryResult(
  val chapterTitle: String,
  val keySentences: List<String>,
  val topKeywords: List<String>,
  val wordCount: Int,
  val estimatedMinutes: Int
)

data class BookAnswerPassage(
  val chapterIndex: Int,
  val chapterTitle: String,
  val passage: String,
  val relevanceScore: Double
)

data class BookQuestionAnswer(
  val question: String,
  val summaryAnswer: String,
  val passages: List<BookAnswerPassage>
)

/**
 * 100% local, zero-server NLP engine for chapter summarization, extractive Q&A, and reading estimates.
 */
object LocalBookIntelligence {

  private val SPANISH_ENGLISH_STOPWORDS = setOf(
    "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a", "en", "con", "por",
    "para", "sin", "sobre", "entre", "hacia", "hasta", "desde", "y", "o", "u", "e", "ni", "que", "quien",
    "cual", "cuyo", "como", "cuando", "donde", "si", "no", "mas", "pero", "sino", "aunque", "porque",
    "pues", "ya", "muy", "tan", "tanto", "todo", "toda", "todos", "todas", "otro", "otra", "otros",
    "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas", "aquel", "aquella", "su", "sus",
    "mi", "mis", "tu", "tus", "nuestro", "nuestra", "se", "le", "les", "lo", "me", "te", "nos", "os",
    "es", "son", "era", "eran", "fue", "fueron", "ser", "estar", "esta", "estaba", "estaban", "ha",
    "han", "habia", "hubo", "haber", "hay", "hacer", "hizo", "dijo", "decia", "puede", "pueden",
    "the", "and", "of", "to", "in", "is", "it", "that", "was", "for", "on", "are", "as", "with",
    "his", "they", "at", "be", "this", "from", "by", "or", "an", "not", "but", "what", "who", "cual",
    "quienes", "cuales", "sobre", "acerca", "pasa", "ocurre", "trata", "libro", "capitulo"
  )

  fun summarizeChapter(chapter: EpubChapter, wordsPerMinute: Int = 210): ChapterSummaryResult {
    val rawText = chapter.blocks
      .filter { it !is EpubBlock.Image }
      .joinToString("\n") { block ->
        when (block) {
          is EpubBlock.Heading -> ""
          is EpubBlock.Paragraph -> block.plainText
          is EpubBlock.Quote -> block.plainText
          is EpubBlock.Image -> ""
        }
      }.trim()

    val words = tokenizeWords(rawText)
    val wordCount = words.size
    val estMinutes = (wordCount / wordsPerMinute.coerceAtLeast(100).toFloat()).let {
      kotlin.math.ceil(it.toDouble()).toInt().coerceAtLeast(1)
    }

    val sentences = splitIntoSentences(rawText)
    if (sentences.isEmpty()) {
      return ChapterSummaryResult(
        chapterTitle = chapter.title,
        keySentences = listOf("Este capítulo contiene principalmente encabezados o ilustraciones."),
        topKeywords = emptyList(),
        wordCount = wordCount,
        estimatedMinutes = estMinutes
      )
    }

    // Build term frequencies excluding stopwords
    val termFreq = HashMap<String, Int>()
    for (w in words) {
      val norm = normalizeToken(w)
      if (norm.length >= 3 && norm !in SPANISH_ENGLISH_STOPWORDS) {
        termFreq[norm] = (termFreq[norm] ?: 0) + 1
      }
    }

    val maxFreq = (termFreq.values.maxOrNull() ?: 1).toDouble()
    val weights = termFreq.mapValues { (_, count) -> count / maxFreq }

    val topKeywords = termFreq.entries
      .sortedByDescending { it.value }
      .take(5)
      .map { it.key.replaceFirstChar { c -> c.titlecase() } }

    // Score each sentence by TF weight + moderate length sweet-spot + opening/closing bonus
    val scored = sentences.mapIndexed { idx, sentence ->
      val sTokens = tokenizeWords(sentence).map(::normalizeToken).filter { it.length >= 3 }
      if (sTokens.isEmpty()) {
        idx to 0.0
      } else {
        val tfSum = sTokens.sumOf { weights[it] ?: 0.0 }
        val normalizedDensity = tfSum / kotlin.math.sqrt(sTokens.size.toDouble())
        val positionBonus = when {
          idx == 0 -> 1.25
          idx == sentences.lastIndex -> 1.12
          else -> 1.0
        }
        val lengthFactor = if (sTokens.size in 8..38) 1.15 else 0.85
        idx to (normalizedDensity * positionBonus * lengthFactor)
      }
    }

    val selectedIndices = scored
      .sortedByDescending { it.second }
      .take(4)
      .map { it.first }
      .sorted()

    val keySentences = selectedIndices.map { sentences[it] }

    return ChapterSummaryResult(
      chapterTitle = chapter.title,
      keySentences = keySentences,
      topKeywords = topKeywords,
      wordCount = wordCount,
      estimatedMinutes = estMinutes
    )
  }

  fun askBook(document: EpubDocument, question: String): BookQuestionAnswer {
    val cleanQuery = question.trim()
    val queryTokens = tokenizeWords(cleanQuery)
      .map(::normalizeToken)
      .filter { it.length >= 2 && it !in SPANISH_ENGLISH_STOPWORDS }
      .distinct()

    if (queryTokens.isEmpty()) {
      return BookQuestionAnswer(
        question = cleanQuery,
        summaryAnswer = "Escribe palabras clave o una pregunta concreta sobre personajes, lugares o hechos del libro.",
        passages = emptyList()
      )
    }

    // Collect all candidate paragraphs across chapters
    data class DocParagraph(val chIdx: Int, val chTitle: String, val text: String, val tokens: List<String>)
    val corpus = mutableListOf<DocParagraph>()
    for (ch in document.chapters) {
      for (block in ch.blocks) {
        val text = when (block) {
          is EpubBlock.Paragraph -> block.plainText
          is EpubBlock.Quote -> block.plainText
          else -> ""
        }
        if (text.length >= 24) {
          corpus.add(
            DocParagraph(
              chIdx = ch.index,
              chTitle = ch.title,
              text = text,
              tokens = tokenizeWords(text).map(::normalizeToken)
            )
          )
        }
      }
    }

    if (corpus.isEmpty()) {
      return BookQuestionAnswer(
        question = cleanQuery,
        summaryAnswer = "No se encontró texto analizable en este libro.",
        passages = emptyList()
      )
    }

    val avgLen = corpus.map { it.tokens.size }.average().coerceAtLeast(1.0)
    val docCount = corpus.size.toDouble()
    val df = queryTokens.associateWith { q ->
      corpus.count { p -> q in p.tokens }.toDouble()
    }

    val k1 = 1.4
    val b = 0.75
    val normalizedQueryPhrase = normalizeToken(cleanQuery)

    val ranked = corpus.mapNotNull { doc ->
      var score = 0.0
      val lenRatio = doc.tokens.size / avgLen
      for (q in queryTokens) {
        val tf = doc.tokens.count { it == q || (q.length >= 4 && it.startsWith(q)) }.toDouble()
        if (tf > 0) {
          val n = df[q] ?: 0.0
          val idf = ln((docCount - n + 0.5) / (n + 0.5) + 1.0)
          score += idf * ((tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * lenRatio)))
        }
      }
      if (normalizedQueryPhrase.length >= 5 && normalizeToken(doc.text).contains(normalizedQueryPhrase)) {
        score += 3.5
      }
      if (score > 0.05) {
        BookAnswerPassage(
          chapterIndex = doc.chIdx,
          chapterTitle = doc.chTitle,
          passage = doc.text,
          relevanceScore = score
        )
      } else null
    }.sortedByDescending { it.relevanceScore }.take(5)

    val summary = if (ranked.isEmpty()) {
      "No encontré menciones directas sobre «$cleanQuery» en este libro. Prueba con sinónimos o nombres propios."
    } else {
      val top = ranked.first()
      "Según ${top.chapterTitle}, el texto señala: «${top.passage.take(240)}${if (top.passage.length > 240) "…" else ""}»"
    }

    return BookQuestionAnswer(
      question = cleanQuery,
      summaryAnswer = summary,
      passages = ranked
    )
  }

  fun estimateTotalWords(document: EpubDocument): Int {
    return document.chapters.sumOf { tokenizeWords(it.plainText).size }
  }

  fun estimateRemainingMinutes(
    totalWords: Int,
    totalUnits: Int,
    progressFraction: Float,
    wordsPerMinute: Int
  ): Int {
    val effectiveWords = if (totalWords > 0) totalWords else totalUnits * 280
    val remainingFraction = (1f - progressFraction).coerceIn(0f, 1f)
    val remainingWords = effectiveWords * remainingFraction
    return kotlin.math.ceil((remainingWords / wordsPerMinute.coerceAtLeast(100)).toDouble()).toInt()
  }

  private fun splitIntoSentences(text: String): List<String> {
    return text.split(Regex("""(?<=[.!?…])\s+|\n+"""))
      .map { it.trim() }
      .filter { it.length >= 25 }
  }

  private fun tokenizeWords(text: String): List<String> {
    return Regex("""[\p{L}\p{N}]+""").findAll(text).map { it.value }.toList()
  }

  fun normalizeToken(token: String): String {
    val decomposed = Normalizer.normalize(token.lowercase(), Normalizer.Form.NFD)
    return decomposed.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
  }
}
