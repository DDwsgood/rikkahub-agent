package me.rerere.rikkahub.data.ai

/** Only advertised to interactive RikkaHub clients, never to headless jobs. */
internal object IntelligentUi {
    const val marker = "RIKKA_UI_V1"
    val prompt = """
        Prefer a normal Markdown answer. Respect explicit plain text, code or structured output requests.
        If an inline comparison, checklist, chart or adjustable budget would clearly help, you may answer with $marker alone on the first line. Then write one component per line in reading order, no fences or variables. Use JSON literal arguments and the user's language. Components:
        Title("heading"), Text("paragraph"), Cards([["label","description"]]), Table(["column"],[["cell"]]), Checklist(["item"]), Chart("bar" or "line",["label"],[number]), Budget("currency",fixedCost,perPerson,minPeople,maxPeople,initialPeople).
        The client handles layout, checked state and arithmetic. Do not repeat headings or invent data. No scripts, actions, or other components.
    """.trimIndent()
}
