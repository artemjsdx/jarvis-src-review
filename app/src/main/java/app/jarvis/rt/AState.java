package app.jarvis.rt;

/** What the assistant is doing right now (labels are the user-visible Russian names). */
public enum AState {
    IDLE("Ожидание"), WAITING("Жду обращения"), LISTENING("Слушаю"), RECOGNIZING("Распознаю"), THINKING("Думаю"),
    TOOL("Выполняю инструмент"), SPEAKING("Говорю"), DONE("Завершено"), ERROR("Ошибка"), CANCELLED("Отменено");

    public final String label;
    AState(String label) { this.label = label; }
}
