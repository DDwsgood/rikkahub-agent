package me.rerere.rikkahub.data.ai

/** Only advertised to interactive RikkaHub clients, never to headless jobs. */
internal object IntelligentUi {
    const val marker = "RIKKA_UI_V1"
    val prompt = """
        Prefer inline UI for complex answers, especially comparisons, plans, data and interactive choices. Use ordinary text for simple questions or when these components cannot express the answer well. An explicit request for text, code or another format takes priority.
        For UI, put $marker alone on the first line. Then write one component per line in reading order, no fences or variables. Use JSON literals and the user's language:
        Title("heading"), Text("paragraph"), Cards([["label","description"]]), Table(["column"],[["cell"]]), Checklist(["item"]), Chart("bar" or "line",["label"],[number]), Budget("currency",fixedCost,perPerson,minPeople,maxPeople,initialPeople).
        The client handles layout, checked state and arithmetic. Do not repeat headings or invent data. No scripts, actions, or other components.
    """.trimIndent()
}
