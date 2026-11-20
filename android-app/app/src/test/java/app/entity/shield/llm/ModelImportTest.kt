package app.entity.shield.llm

import app.entity.shield.llm.ModelImport.Kind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Files picked from Downloads are recognised by content, whatever the browser renamed them to. */
class ModelImportTest {
    private fun head(f: File) = f.inputStream().use { i -> ByteArray(64).let { b -> b.copyOf(i.read(b)) } }

    @Test fun realFilesAreRecognisedEvenWhenRenamed() {
        val pte = File("../../models/llama3_2_1b_instruct_qlora_ctx2048.pte")
        val tok = File("../../release/v0.4.0/tokenizer.model")
        if (pte.exists()) assertEquals(Kind.MODEL, ModelImport.classify(head(pte), "download (1).bin"))
        if (tok.exists()) assertEquals(Kind.TOKENIZER, ModelImport.classify(head(tok), "tokenizer (1).model"))
    }

    @Test fun headersAloneDecide() {
        val et = byteArrayOf(0x40, 0, 0, 0) + "ET12eh00".toByteArray()
        assertEquals(Kind.MODEL, ModelImport.classify(et, "whatever"))
        assertEquals(Kind.TOKENIZER, ModelImport.classify("IQ== 0\nIg== 1\n".toByteArray(), "file"))
        assertEquals(Kind.UNKNOWN, ModelImport.classify("%PDF-1.7".toByteArray(), "notes.pdf"))
        assertEquals(Kind.MODEL, ModelImport.classify(ByteArray(0), "x.pte"))
    }

    @Test fun modelIsSavedUnderAName() {
        assertEquals(ModelDownload.MODEL.name, ModelImport.modelTarget("llama3_2_1b_instruct_qlora_ctx2048 (1).pte", 5))
        assertEquals(ModelDownload.MODEL.name, ModelImport.modelTarget("model.bin", ModelDownload.MODEL.bytes))
        assertEquals("llama3_2_1b_instruct_8da4w_ctx2048.pte", ModelImport.modelTarget("Llama-8DA4W.pte", 5))
        assertEquals("llama_imported.pte", ModelImport.modelTarget("custom.pte", 5))
    }
}
