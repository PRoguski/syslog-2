# syslog-parser

Serwis Kafka Streams parsujący syslog z Cisco 8000 (IOS XR) na JSON, według
reguł zdefiniowanych w YAML. Zobacz `plan-syslog-parser.md` (w historii tego
brancha / w opisie zadania) po pełny plan.

## Status implementacji

Zaimplementowane są punkty 1–6 z „Kolejności prac" planu:

1. Szkielet projektu (Gradle/Java 21, picocli, Jackson YAML, records konfiguracji)
2. `expr/` — mikro-DSL pipeline'ów (`{{ grupa | filtr | filtr(arg) }}`), rejestr
   filtrów z type-checkiem, testy jednostkowe
3. `ConfigValidator` — fail-fast, wszystkie błędy konfiguracji zebrane razem
4. `RuleEngine` — dopasowanie regex → `ObjectNode`, `first_match`/`all_matches`, `prefilter`
5. CLI (`validate`, `dry-run`, `test`) + `GoldenTestRunner` + JUnit `@TestFactory`
6. Topologia Kafka Streams (`process → split → to(TopicNameExtractor)`) + `TopologyTestDriver`,
   `on_no_match` (`dlq`/`drop`/`passthrough`), `run` łączący to z realnym Kafką

**Poza zakresem** (punkty 7–14 planu): metryki Micrometer/Prometheus, `/healthz`,
JSON logi przez logstash encoder, Testcontainers, obraz Jib, manifesty K8s,
hot-reload `rules.yaml`. Podstawowe elementy z §9 (obsługa błędów, exception
handlery Kafka Streams) są zaimplementowane w zakresie potrzebnym, żeby `run`
faktycznie działał.

## Build i testy

```
gradle build          # kompilacja + wszystkie testy (JUnit, TopologyTestDriver, golden)
gradle installDist     # buduje uruchamialny skrypt w build/install/syslog-parser/bin/
```

## CLI

```
syslog-parser validate --service config/service.yaml --rules config/rules.yaml
syslog-parser dry-run  --rules config/rules.yaml "<187>RP/0/RP0/CPU0:...UP-DOWN..."
syslog-parser test     --rules config/rules.yaml --tests tests/golden.yaml
syslog-parser run      --service config/service.yaml --rules config/rules.yaml
```

`dry-run` i `test` nie potrzebują `service.yaml` (żadnego połączenia z Kafką) —
liczy się tylko `rules.yaml`. `run` wymaga działającego brokera pod adresem z
`kafka.bootstrap`.

## Struktura

- `config/service.yaml`, `config/rules.yaml` — przykładowa, w pełni działająca konfiguracja
- `tests/golden.yaml` — golden testy (bramka dla zmian w `rules.yaml`)
- `samples/cisco8000-sample.log` — przykładowe surowe linie syslog
- `src/main/java/pl/example/syslogparser/` — kod wg architektury z planu
  (`config/`, `expr/`, `engine/`, `streams/`, `cli/`, `golden/`)
