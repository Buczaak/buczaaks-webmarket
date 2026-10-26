# Third-party dependencies

The plugin **does not bundle (shade) any third-party libraries** - the `.jar` contains only our own code,
and the website does not load any external scripts, fonts or images.

| Component | Role | License | Notes |
|---|---|---|---|
| Paper API (`io.papermc.paper:paper-api`) | server API (`provided`, supplied by the server) | GPL-3.0 inherited from Bukkit/Spigot (some contributions MIT) | The commonly accepted community position is that plugins using only the API are not forced to be GPL. This is not legal advice - confirm with a lawyer if you plan to sell the plugin. |
| VaultAPI (`com.github.MilkBowl:VaultAPI`) | economy integration (`provided`, supplied by the Vault plugin) | LGPL-3.0 | Dynamic linking, no code copied into the jar. |
| Gson | JSON (supplied by the Paper server at runtime) | Apache-2.0 | Not bundled. |
| JDBC SQLite / MySQL | database (supplied by the Paper server) | Apache-2.0 / GPL-2.0 with FOSS exception (Connector/J) | Not bundled. |
| sqlite-jdbc, JUnit 5 | tests only (`test` scope) | Apache-2.0 / EPL-2.0 | Not included in the jar. |

"Minecraft", "Mojang", "Paper" and "Vault" are trademarks of their respective owners; this plugin is not endorsed by or affiliated with them.
