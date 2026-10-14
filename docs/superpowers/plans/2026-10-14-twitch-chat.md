# Twitch chat implementation

Approved scope: the user's “Реализуй” refers to ТЗ 2.0 and its lightweight checks.
Work in the current checkout to preserve the existing uncommitted game implementation.
No release audits, dedicated server requirements or benchmark gates.

1. Add local channel/login settings and a separate ignored token file.
2. Add a background TLS IRC connection and tagged-message parsing.
3. Map six fixed commands to the sender's Twitch ID; queue at most 256 commands.
4. Drain up to four commands per game tick through the existing ViewerParrotApi.
5. Bind the connection to the single-player world; add /vp twitch connect/disconnect/status.
6. Run focused Twitch tests and assemble once; update setup docs and acceptance status.

Live Twitch testing needs the user's local channel/login/token. Do not request a token
in chat, read unrelated account secrets or claim a live test without these settings.

Transport and settings are implemented in parallel in separate files; root handles
ownership, bounded queue, game lifecycle and user documentation.

Completed: settings, background IRC transport, sender-owned command inbox, world
lifecycle hooks and game controls. Focused Twitch tests and assemble passed.
JAR installed in Prism 1.21.1-fabric-dev with previous JAR backed up. Empty local
setup files prepared without reading or replacing secrets. Live chat remains
unverified until the user provides channel/login and stores their token locally.
