# Сообщения коммитов

[Conventional Commits](https://www.conventionalcommits.org/), по-английски:

```
feat(mod): add seeking by dragging the progress bar
fix(bridge): reject playlist links with a clear error
docs: describe the release process
```

- типы: `feat`, `fix`, `refactor`, `docs`, `chore`, `build`;
- области: `mod`, `bridge`; без области — всё сразу или документация;
- несовместимое изменение протокола — `!` после области и строка
  `BREAKING CHANGE:` в теле;
- тело — что и зачем, списком, если пунктов несколько.

Соавторов-агентов и подписей «сгенерировано» не добавлять.
