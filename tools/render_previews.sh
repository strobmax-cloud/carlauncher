#!/bin/bash
# Renders preview PNGs from the HTML simulator (same file the user opens).
cd "$(dirname "$0")/.."
shot(){ /usr/bin/google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=4000 --window-size=1280,720 --screenshot="$PWD/$1" "file://$PWD/CarLauncher-preview.html#chrome=0&freeze=1&time=21:47&$2" >/dev/null 2>&1; }
for l in cockpit map media arc widgets tiles minimal smart premium sport nordic; do shot preview-layout-$l.png "layout=$l&theme=glass&state=playing&cover=0"; done
shot preview-home.png "layout=cockpit&theme=glass&state=playing&cover=0"
shot preview-idle.png "layout=cockpit&theme=glass&state=idle"
for t in glass aurora bento synth city; do shot preview-theme-$t.png "layout=widgets&theme=$t&state=playing&cover=0"; done
shot preview/sim-settings.png "layout=cockpit&theme=glass&screen=3"
shot preview/sim-arc-synth.png "layout=arc&theme=synth&state=playing&cover=0"
shot preview/sim-media-cover.png "layout=media&theme=city&state=playing&cover=1"
shot preview/sim-map-idle.png "layout=map&theme=aurora&state=idle"
shot preview/sim-nordic-dark.png "layout=nordic&theme=glass&state=playing&cover=0&nordic=dark"
shot preview/sim-sport-synth.png "layout=sport&theme=synth&state=playing&cover=0"
shot preview/sim-smart-aurora.png "layout=smart&theme=aurora&state=playing&cover=0"
shot preview/sim-premium-city.png "layout=premium&theme=city&state=playing&cover=0"
shot preview/sim-settings-brand.png "layout=minimal&theme=glass&screen=3"
