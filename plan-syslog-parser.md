# Plan programowania: serwis przetwarzania syslog z Cisco 8000

## 1. Cel i zakres

Serwis odbiera z kolejki Kafka surowe komunikaty syslog (string) pochodzące z urządzeń Cisco serii 8000 (IOS XR), dopasowuje je do skonfigurowanych reguł, wyciąga pola z nazwanych grup regex, renderuje JSON według template'u i wysyła wynik na topic Kafka przypisany do reguły.

Główne założenia:

- **Kafka in → reguła (regex + template + topic) → JSON → Kafka out**
- Obsługa nowych typów komunikatów przez dopisanie reguły w konfiguracji, **bez zmian w kodzie**
- Każda reguła ma własny regex, własny template i własny topic wyjściowy
- Semantyka dostarczania: at-least-once

Przykładowy komunikat wejściowy (IOS XR):

```
<187>RP/0/RP0/CPU0:Aug 26 10:14:22.531 UTC: ifmgr[402]: %PKT_INFRA-LINK-3-UPDOWN : Interface HundredGigE0/0/0/0, changed state to Down
```

Kluczowe pola: PRI (facility/severity), lokalizacja karty/CPU, timestamp, proces[pid], mnemonic `%FACILITY-SUBFACILITY-SEVERITY-NAME`, treść.

## 2. Analiza wstępna

- Zebrać korpus realnych komunikatów z urządzeń (różne procesy, severity, komunikaty wieloliniowe).
- Sprawdzić konfigurację urządzeń: format (`logging format rfc5424` vs domyślny), strefa czasowa, milisekundy w timestampie, czy włączony `logging sequence` (numer sekwencyjny na początku linii).
- Oszacować wolumen (msg/s w normie i w burście, np. przy flapowaniu interfejsów) — determinuje liczbę partycji i workerów.
- Pułapki formatu: brak roku w timestampie, wielokrotne spacje, komunikaty wieloliniowe (dumpy), bardzo długie linie.

## 3. Model konfiguracji

Konfiguracja podzielona na dwa pliki:

- `service.yaml` — Kafka, metryki, parametry stałe (zmienia się rzadko)
- `rules.yaml` — lista reguł (zmienia się często, może mieć osobne uprawnienia i osobny przegląd w repo)

### 3.1 `service.yaml`

```yaml
kafka:
  bootstrap: "kafka1:9092,kafka2:9092"
  input:
    topic: syslog-raw
    group_id: syslog-parser
  producer:
    idempotent: true

routing:
  strategy: first_match        # first_match | all_matches
  on_no_match:
    action: dlq                # dlq | drop | passthrough
    topic: syslog-unmatched

metrics:
  port: 9090
```

### 3.2 `rules.yaml`

```yaml
defines:
  xr_prefix: '^<(?P<pri>\d+)>(?P<location>RP/\S+):(?P<ts>.+?) (?P<tz>\w+): (?P<process>[\w-]+)\[(?P<pid>\d+)\]: '

defaults:
  template:
    received_at: "{{ now() }}"
    severity:    "{{ pri | pri_severity }}"
    facility:    "{{ pri | pri_facility }}"
    location:    "{{ location }}"
    raw:         "{{ raw }}"

rules:
  # Kolejność ma znaczenie przy first_match: reguły szczegółowe wyżej, catch-all na końcu.

  - name: xr_link_updown
    enabled: true
    prefilter: "LINK-3-UPDOWN"           # tani test przed regexem (opcjonalny)
    regex: '{{xr_prefix}}%(?P<mnemonic>PKT_INFRA-LINK-\d-UPDOWN) : Interface (?P<interface>\S+), changed state to (?P<state>\w+)$'
    output:
      topic: net-interface-events
      key: "{{ interface }}"             # klucz partycjonowania (opcjonalny)
    template:
      event_type:  "interface_state"
      interface:   "{{ interface }}"
      state:       "{{ state | lower }}"
      device_time: "{{ ts | parse_ts('%b %d %H:%M:%S.%f', tz) }}"

  - name: xr_generic
    regex: '{{xr_prefix}}%(?P<mnemonic>[\w-]+) : (?P<message>.*)$'
    output:
      topic: syslog-json
    template:
      process:  "{{ process }}"
      pid:      "{{ pid | int }}"
      mnemonic: "{{ mnemonic }}"
      message:  "{{ message }}"
      source:   "cisco-8000"
```

### 3.3 Decyzje projektowe

| Temat | Decyzja |
|---|---|
| Grupy regex | Wyłącznie **nazwane** grupy — stanowią kontrakt między regexem a template'em |
| Silnik template'u | Gotowy silnik (Jinja2 / Go `text/template` / Handlebars, zależnie od języka) + własne funkcje: `pri_severity`, `pri_facility`, `parse_ts`, `now`, `int`, `lower`, `default` |
| Typy w JSON | Template zwraca stringi; rzutowanie przez filtry (`int`, `float`, `bool`) |
| Pola statyczne | Wartość bez `{{ }}` w template'ie = literał |
| `defines` | Nazwane fragmenty regexów wstawiane do reguł (np. wspólny prefix IOS XR) |
| `defaults.template` | Pola dziedziczone przez wszystkie reguły; reguła może je nadpisać |
| `first_match` | Pierwsza pasująca reguła wygrywa; szybkie |
| `all_matches` | Jedna wiadomość może trafić na kilka topiców; testuje wszystkie regexy |
| `prefilter` | Tani `contains` sprawdzany przed regexem — istotne przy dziesiątkach reguł |
| Klucz i nagłówki Kafka | Przepisywane z wiadomości wejściowej na wyjściową (chyba że reguła definiuje `output.key`) |
| Producent | Jeden współdzielony producent, topic podawany per wiadomość |

## 4. Pipeline przetwarzania

```
consume → dekodowanie (UTF-8) → prefilter → regex match → kontekst
       → render template → serializacja JSON → produce → commit offsetu po ack
```

Kontekst dostępny w template'ie: grupy regex, `raw`, metadane Kafka (`kafka.key`, `kafka.headers`, `kafka.partition`, `kafka.offset`, `kafka.timestamp`).

### Obsługa błędów

- **Brak dopasowania** → wg `routing.on_no_match`:
  - `dlq` — do topicu DLQ z `raw`, powodem, partycją/offsetem
  - `drop` — odrzucenie z inkrementacją metryki
  - `passthrough` — na domyślny topic z flagą `parse_error: true`
- **Błąd renderowania template'u** (np. zły format daty) → traktowany jak brak dopasowania; nigdy nie zatrzymuje konsumenta.
- Błąd produce → retry z backoffem; brak commitu offsetu do skutku.

### Semantyka dostarczania

- Commit offsetu dopiero po potwierdzeniu produce.
- Producent idempotentny.
- Odbiorcy JSON-a muszą tolerować duplikaty. Exactly-once (transakcje Kafka) — do rozważenia w późniejszej fazie.

## 5. Walidacja konfiguracji przy starcie

Fail-fast: błędna konfiguracja = serwis nie startuje, z czytelnym komunikatem, np.  
`rule "xr_link_updown": template uses group "iface" not present in regex`.

Sprawdzane:

- unikalne `name` reguł
- regex kompiluje się; podstawione `defines`
- wszystkie grupy używane w template'ie i `output.key` istnieją w regexie (ostrzeżenie o nieużywanych)
- `output.topic` niepusty
- funkcje w template'ie istnieją, poprawna liczba argumentów
- bezpieczeństwo regexów: silnik bez katastrofalnego backtrackingu (RE2 — Go, Rust `regex`) albo timeout na dopasowanie (Python: moduł `regex`)

## 6. Narzędzia CLI

- `service validate --config ...` — tylko walidacja
- `service dry-run --config ... "<linia syslog>"` — wypisuje dopasowaną regułę, topic i JSON bez Kafki; podstawowe narzędzie przy pisaniu reguł
- `service test --config ...` — uruchamia golden testy (patrz §8)

## 7. Wydajność

- Regexy i template'y kompilowane raz przy starcie.
- Batch consume / produce; producent asynchroniczny z callbackiem; commit offsetów partiami.
- Równoległość = liczba partycji wejściowych; jeden worker na partycję zachowuje kolejność.
- `prefilter` przed regexem przy dużej liczbie reguł.

## 8. Testy

### Golden testy (część konfiguracji)

Skoro reguły żyją poza kodem, testy też. Katalog `tests/` obok `rules.yaml`:

```yaml
- input: '<187>RP/0/RP0/CPU0:Aug 26 10:14:22.531 UTC: ifmgr[402]: %PKT_INFRA-LINK-3-UPDOWN : Interface HundredGigE0/0/0/0, changed state to Down'
  expect_rule: xr_link_updown
  expect_topic: net-interface-events
  expect_json:
    event_type: interface_state
    interface: HundredGigE0/0/0/0
    state: down
    severity: 3
```

Pilnują jednocześnie poprawności regexów, template'ów i **kolejności reguł** (catch-all przechwytujący coś, co miała złapać reguła szczegółowa, wywala test). Bramka w CI dla zmian konfiguracji.

### Pozostałe

- Testy jednostkowe funkcji template'u (daty bez roku, PRI → severity/facility, rzutowania).
- Testy integracyjne z Kafką w kontenerze (testcontainers / docker-compose): happy path, DLQ, restart w połowie batcha (brak utraty, dopuszczalne duplikaty).
- Test obciążeniowy: generator wrzuca N msg/s na topic wejściowy; mierzony lag i czas przetwarzania.
- Testy odporności: śmieciowe bajty, linie 8+ KB, niedostępny broker wyjściowy.

## 9. Obserwowalność

Metryki (Prometheus):

- `consumed_total`, `produced_total{rule,topic}`, `matched_total{rule}`, `unmatched_total`, `dlq_total`, `template_errors_total{rule}`
- `match_duration_seconds{rule}`, `processing_duration_seconds`
- `consumer_lag{partition}`

Healthcheck: połączenie z Kafką + „ostatnia wiadomość przetworzona X s temu".  
Logi serwisu strukturalne (JSON), z partycją/offsetem przy każdym błędzie — i oczywiście nie przez ten sam kanał, który serwis przetwarza.

## 10. Wdrożenie i cykl życia konfiguracji

- Kontener; konfiguracja z plików, sekrety z env.
- Graceful shutdown: dokończ batch, flush producenta, commit, zamknij konsumenta.
- Faza 1: zmiana konfiguracji = restart poda.
- Faza 2: hot-reload — nowa konfiguracja walidowana i podmieniana atomowo; przy błędzie stara zostaje.

## 11. Kolejność prac

1. Schemat konfiguracji z listą reguł, `defines`, `defaults`; walidacja fail-fast
2. Silnik dopasowania (`first_match` / `all_matches`, `prefilter`) + template + funkcje pomocnicze
3. CLI: `validate`, `dry-run`, `test` (golden testy) — **przed** kodem Kafki
4. Konsument → router → współdzielony producent → commit po ack
5. `on_no_match`, DLQ, obsługa błędów template'u i produce
6. Metryki per reguła, healthcheck, graceful shutdown
7. Testy integracyjne i obciążeniowe
8. Hot-reload konfiguracji
9. Rozszerzenia (wg potrzeb): pola z nagłówków Kafka, transakcje exactly-once, wyjście na inne sinki niż Kafka

## 12. Decyzje otwarte

- Język i biblioteki (np. Python: `confluent-kafka` + Jinja2; Go: `franz-go` + `text/template`; Java: Kafka Streams)
- Czy `all_matches` jest potrzebne w pierwszej wersji
- Format i topic DLQ — czy konsumowany przez kogoś, czy tylko do diagnostyki
