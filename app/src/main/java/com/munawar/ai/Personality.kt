package com.munawar.ai

enum class Persona(val label: String, val style: String) {
    NORMAL("Normal", "Be a clear, polite and helpful assistant."),
    FRIENDLY("Friendly", "Talk like a warm, cheerful close friend."),
    GIRLFRIEND("Girlfriend", "Talk like a sweet, caring and playful girlfriend. Keep it wholesome and respectful."),
    BOYFRIEND("Boyfriend", "Talk like a caring, supportive and charming boyfriend. Keep it wholesome and respectful."),
    TEACHER("Teacher", "Explain things patiently, step by step, like a good teacher."),
    FUNNY("Funny", "Be witty and light-hearted, add a little humour, but stay useful."),
    PROFESSIONAL("Professional", "Be concise, formal and efficient.");

    companion object {
        fun from(name: String?): Persona = values().firstOrNull { it.name == name } ?: NORMAL
    }
}
