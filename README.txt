ConfineLock - plugin na Purpur 1.21.1 (Java 21)
================================================

DZIALANIE
  /confinelock            wlacza blokade - kto od teraz zginie w ConfineSMP, nie wejdzie tam ponownie
  /confineunlock          wylacza blokade i odblokowuje wszystkich
  /confineunlock <gracz>  odblokowuje jednego gracza
  /confinelist            status i lista zablokowanych

UPRAWNIENIA
  confinesmp.admin   komendy (domyslnie OP)
  confinesmp.bypass  omija blokade (domyslnie nikt; uwaga na uprawnienie * w LuckPerms)

BUDOWANIE
  1) JDK 21 + Maven:   mvn clean package
  2) Plik wynikowy:    target/ConfineLock.jar
  3) Wrzuc do plugins/ i zrestartuj serwer.

  Bez instalowania czegokolwiek: wrzuc te pliki do repozytorium na GitHub.
  Workflow .github/workflows/build.yml zbuduje .jar (zakladka Actions -> Artifacts).

KONFIGURACJA
  plugins/ConfineLock/config.yml
    confine-worlds   - swiaty trybu ConfineSMP (nazwy z /mv list)
    fallback-world   - swiat lobby, do ktorego trafia gracz po smierci
