#!/data/data/com.termux/files/usr/bin/bash
# Sends each utterance as ogg/opus to Groq and reports whether the transcript contains the wake word.
cd "$(dirname "$0")/../.."
for f in build/meas/wav/pos_*.pcm build/meas/wav/neg_*.pcm; do
  n=$(basename "$f" .pcm)
  case "$n" in neg_05*|neg_06*|neg_07*|neg_09*|neg_11*|neg_12*|neg_13*|neg_14*) ;; pos_*) ;; *) continue;; esac
  ffmpeg -loglevel error -y -f s16le -ar 16000 -ac 1 -i "$f" -c:a libopus -b:a 16k build/meas/c_$n.ogg
  t=$(curl -sS -X POST https://api.groq.com/openai/v1/audio/transcriptions -H @build/meas/groq.hdr \
      -F "file=@build/meas/c_$n.ogg;type=audio/ogg" -F model=whisper-large-v3-turbo -F language=ru -F response_format=json \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('text',''))" 2>/dev/null)
  if echo "$t" | python3 -c "import sys,re; s=sys.stdin.read().lower(); sys.exit(0 if re.search(r'джарв|джерв|жарв|джарвис', s) else 1)"; then h=CONFIRMED; else h=rejected; fi
  echo "$n $h :: $t"
  sleep 1
done
echo DONE
