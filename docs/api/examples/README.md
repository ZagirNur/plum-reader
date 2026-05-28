# API Examples

Полный happy-path и типичные ошибки в curl. Скрипт `examples/happy-path.sh` прогоняет всё подряд против локального сервера.

| Файл | Что |
|------|-----|
| [happy-path.sh](./happy-path.sh) | end-to-end: register → upload → wait ready → read → vocabulary |
| [auth.http](./auth.http) | register/login/me + ошибки |
| [upload.http](./upload.http) | upload + dedup + 422 |
| [read.http](./read.http) | list/detail/pages/progress + 404/409 |
| [vocabulary.http](./vocabulary.http) | top words / single word / 409 |

`.http` файлы — формат IntelliJ HTTP Client / VS Code REST Client. Можно запускать прямо из IDE.
