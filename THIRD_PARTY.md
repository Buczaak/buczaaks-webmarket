# Zależności zewnętrzne

Plugin **nie zawiera (nie „shade'uje”) żadnych bibliotek stron trzecich** – w pliku `.jar` jest wyłącznie kod własny,
a strona WWW nie ładuje żadnych zewnętrznych skryptów, czcionek ani obrazów.

| Składnik | Rola | Licencja | Uwagi |
|---|---|---|---|
| Paper API (`io.papermc.paper:paper-api`) | API serwera (`provided`, dostarcza serwer) | GPL-3.0 dziedziczona z Bukkit/Spigot (część wkładów MIT) | Powszechnie przyjmowane stanowisko społeczności: wtyczki używające samego API nie są zmuszone do GPL. To nie jest porada prawna – przy sprzedaży warto potwierdzić z prawnikiem. |
| VaultAPI (`com.github.MilkBowl:VaultAPI`) | integracja z ekonomią (`provided`, dostarcza plugin Vault) | LGPL-3.0 | Linkowanie dynamiczne, bez kopiowania kodu do jar-a. |
| Gson | JSON (dostarczany przez serwer Paper w czasie działania) | Apache-2.0 | Nie jest dołączony do jar-a. |
| JDBC SQLite / MySQL | baza danych (dostarczane przez serwer Paper) | Apache-2.0 / GPL-2.0 z wyjątkiem FOSS (Connector/J) | Nie są dołączone do jar-a. |
| sqlite-jdbc, JUnit 5 | wyłącznie testy (`test`) | Apache-2.0 / EPL-2.0 | Nie trafiają do jar-a. |

Nazwy „Minecraft”, „Mojang”, „Paper”, „Vault” należą do ich właścicieli; plugin nie jest przez nich wspierany ani zatwierdzony.
