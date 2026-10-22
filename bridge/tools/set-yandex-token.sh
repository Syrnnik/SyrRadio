#!/bin/zsh
# Спрашивает ссылку с токеном Яндекс Музыки (ввод не виден), вырезает токен,
# кладёт его в .env bridge на сервере и перезапускает bridge. Сам токен нигде
# не печатается и не попадает в историю терминала.
#
#   zsh bridge/tools/set-yandex-token.sh
#
# Куда подключаться — SSH (команда ssh) и DIR (папка bridge на сервере), как в deploy.sh.
SSH="${SSH:-servnnik-local-ssh -o BatchMode=yes}"
DIR="${DIR:-minecraft/syrradio-bridge}"

read -rs "T?Вставь ссылку https://music.yandex.ru/#access_token=... (или сам токен) и нажми Enter: " || exit 1
echo
# Из ссылки берём то, что между access_token= и первым &; голый токен не меняется.
T=${T#*access_token=}
T=${T%%&*}
T=${T//[[:space:]]/}
case "$T" in
  y0_*) ;;
  *) echo "Это не похоже на токен (должен начинаться с y0_). Ничего не меняю."; exit 1 ;;
esac

# Токен идёт на сервер через stdin, а не аргументом: так его не видно в списке процессов.
printf '%s' "$T" | ${=SSH} '
  cd ~/'"$DIR"' || exit 1
  t=$(cat)
  if grep -q "^YANDEX_TOKEN=" .env; then
    sed -i.bak "s|^YANDEX_TOKEN=.*|YANDEX_TOKEN=$t|" .env && rm -f .env.bak
  else
    echo "YANDEX_TOKEN=$t" >> .env
  fi
  echo "токен в .env: $(grep -c "^YANDEX_TOKEN=y0" .env)"
  docker compose up -d 2>&1 | tail -1
'
unset T
echo "Готово."
