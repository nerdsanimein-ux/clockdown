# Alert sounds

The five alert sounds in `app/src/main/res/raw/` (`alarm_chime`, `alarm_bell`, `alarm_pulse`, `alarm_marimba`,
`alarm_rise`) are original work. They are generated from scratch by `tools/make_sounds.py` using only sine waves and
envelopes: nothing is sampled, recorded or copied from anywhere.

**Licence:** dedicated to the public domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Use,
change and redistribute them freely, no attribution needed.

To change a sound, edit the script and run `python tools/make_sounds.py` (needs numpy and ffmpeg with libvorbis).
