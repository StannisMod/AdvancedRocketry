# Project rules — AdvancedRocketry

## Communication
- Общайся с пользователем **на русском языке** (весь текст ответов, объяснения,
  вопросы). Код, идентификаторы, имена коммитов и технические термины — как
  принято (обычно английский). Respond to the user in Russian.

## Test harness runs (RFG)
- Любой запуск MC-harness (`testServer`, `testClient`, `runServer`, `runClient`,
  forge-test-framework) **оборачивай в жёсткий таймаут**, напр.
  `timeout --signal=KILL 360 ./gradlew testServer ... --no-daemon`, и пиши
  полный вывод в файл, а не через `tail` (он скрывает прогресс до завершения).
  Причина: незакрытый прогон однажды завис на ~10.5 ч.
- Сборка/тесты RFG требуют JDK 25 для запуска Gradle:
  `export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9`.
