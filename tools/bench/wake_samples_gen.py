import asyncio, os, subprocess, sys
import edge_tts

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
VOICES = ["ru-RU-SvetlanaNeural", "ru-RU-DmitryNeural"]

POS = [
    "Джарвис, включи музыку",
    "Джарвис, который час?",
    "Джарвис, что ты сейчас делаешь?",
    "Джарвис, создай заметку",
    "Джарвис",
    "Эй, Джарвис, привет",
    "джарвис, останови музыку",
    "Джарвис, какая сегодня погода",
]

NEG = [
    "Привет, как дела у тебя сегодня?",
    "Мы обсудим это на совещании в понедельник.",
    "Кофе уже остыл, но всё равно вкусный.",
    "Давай встретимся возле метро в семь вечера.",
    "Не забудь купить молоко и хлеб по дороге домой.",
    "Жарвис из соседнего отдела опять опоздал.",
    "Журнал выйдет в следующем месяце, я уже видел макет.",
    "Джордж сказал, что посылка пришла вчера.",
    "Ну и погода сегодня, зонт нужен обязательно.",
    "Включи, пожалуйста, свет в коридоре, здесь темно.",
    "Музыка в этом кафе слишком громкая для разговора.",
    "Я же говорил, что стоит проверить расписание поездов.",
    "Сколько времени займёт дорога до аэропорта?",
    "Отправь, пожалуйста, отчёт бухгалтерии до пятницы.",
    "Мы решили оставить старый план и не менять бюджет.",
    "Ребёнок уснул, давайте говорить тише.",
    "Какой фильм посмотрим сегодня вечером?",
    "Нам нужно обновить программу на всех компьютерах в офисе.",
    "Там впереди ремонт, объезд через соседнюю улицу.",
    "Спасибо большое, это было очень полезно.",
    "Заметка для себя: позвонить маме в субботу.",
    "Сегодня хороший день, чтобы поехать на дачу.",
    "Заказ доставят курьером между двенадцатью и двумя часами.",
    "Я не понимаю, почему это не работает с первого раза.",
    "Как называется эта улица, я здесь впервые.",
    "Завтра будет дождь, поэтому возьми куртку.",
]

async def synth(text, voice, path):
    for attempt in range(5):
        try:
            await edge_tts.Communicate(text, voice).save(path)
            return
        except Exception as e:
            print("retry", attempt, type(e).__name__, flush=True)
            await asyncio.sleep(3 + attempt * 3)
    raise RuntimeError("tts failed: " + text)

def to_pcm(mp3, pcm):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ac", "1", "-ar", "16000", "-f", "s16le", pcm], check=True)

async def main():
    jobs = []
    for i, t in enumerate(POS):
        for v in VOICES:
            jobs.append(("pos_%02d_%s" % (i, v.split("-")[2][:4]), t, v))
    for i, t in enumerate(NEG):
        for v in VOICES[:1] if i % 2 else VOICES[1:]:
            jobs.append(("neg_%02d_%s" % (i, v.split("-")[2][:4]), t, v))
    for name, text, voice in jobs:
        mp3 = os.path.join(OUT, name + ".mp3")
        pcm = os.path.join(OUT, name + ".pcm")
        if not os.path.exists(pcm):
            try:
                await synth(text, voice, mp3)
            except RuntimeError as e:
                print("SKIP", name, e, flush=True)
                continue
            to_pcm(mp3, pcm)
            os.remove(mp3)
            await asyncio.sleep(2)
        print(name, text, flush=True)

asyncio.run(main())
