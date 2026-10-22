# Площадки

Код: `bridge/app/sources/`, список — `bridge/app/registry.py`. Побеждает
первая площадка, чей шаблон подошёл к ссылке.

| id | Что | Чем |
|----|-----|-----|
| `yandex` | Яндекс Музыка | `yandex-music`, токен аккаунта с Плюсом |
| `youtube` | YouTube | yt-dlp, прокси `PROXY_YOUTUBE` |
| `soundcloud` | SoundCloud | yt-dlp, прокси `PROXY_OTHER` |
| `other` | всё остальное, что понимает yt-dlp | yt-dlp, прокси `PROXY_OTHER` |

- **Яндекс** ловит весь домен `music.yandex.*`, чтобы на альбом ответить
  понятной ошибкой. Качает лучший mp3; если отдают только 30-секундный
  отрывок — отказ «проверь подписку». Без токена площадка выключена.
  Отметки о прослушивании — только с `YANDEX_REPORT_PLAYS=true`.
- **yt-dlp** для YouTube нужен deno (есть в образе) — им решаются
  JS-проверки. YouTube регулярно ломает загрузчики, поэтому контейнер при
  каждом старте подтягивает свежий yt-dlp.
- У YouTube из названия убирается повтор исполнителя
  («Rick Astley - Never Gonna…» → «Never Gonna…»).

## Новая площадка

1. Класс-наследник `Source` (`sources/base.py`): `matches(url)`,
   `resolve_url(url)`, `download(cand, dir)`, при желании `report_played`.
2. Ошибку, которую надо показать игроку, бросать `SourceError(текст)`.
   Если повторять бессмысленно — `SourceError(текст, retry=False)`.
3. Добавить в `build_sources()` до `other`.

Мод при этом не меняется.
