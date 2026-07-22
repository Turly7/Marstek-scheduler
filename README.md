# Marstek Scheduler (Android)

Application Android (Kotlin + Jetpack Compose) permettant de :
1. **Découvrir** les batteries Marstek présentes sur le réseau Wi‑Fi local (broadcast UDP `Marstek.GetDevice`).
2. Pour chaque batterie trouvée, définir une **plage horaire "Manuel"** (début/fin + puissance) et une
   **heure de retour en "Auto"** (autoconsommation).
3. Programmer ces bascules **quotidiennement**, de façon autonome, via des alarmes système
   (équivalent Android d'une tâche cron), sans dépendre de l'app Marstek officielle.

---

## ⚠️ Ce que cette app fait réellement (et ne fait pas)

- **Découverte réseau réelle**, pas une lecture de l'app Marstek : il n'existe aucune API publique
  permettant d'interroger l'app Marstek pour connaître les appareils qu'elle a enregistrés. L'app
  envoie donc elle‑même un broadcast UDP `Marstek.GetDevice` sur le réseau et écoute les réponses —
  exactement le mécanisme décrit section 2.2.2 de la documentation officielle.
- **Aucune authentification n'existe dans le protocole Marstek** (JSON‑RPC en clair sur UDP). La
  sécurité repose donc entièrement sur la sécurité du réseau Wi‑Fi local (voir section Sécurité).
- Le **sens exact de `power`** en mode Manuel (charge vs décharge) n'est pas documenté par Marstek.
  À valider empiriquement sur votre installation avant usage régulier (voir TODO).

---

## Prérequis

### Côté installation / matériel
- Une batterie **Venus E** (ou C/D/A) avec l'**Open API activée** dans l'app Marstek officielle, et
  un **port UDP** défini (par défaut 30000 ; recommandé entre 49152 et 65535 pour limiter les
  collisions avec d'autres services).
- Idéalement, une **IP statique** (ou réservation DHCP) pour chaque batterie sur votre routeur — sans
  ça, la découverte automatique reste indispensable à chaque changement d'IP.
- Le téléphone Android doit être **connecté au même réseau Wi‑Fi local** que les batteries à chaque
  fois qu'une commande doit être envoyée (recherche ou bascule programmée). Un téléphone hors du
  Wi‑Fi domestique au moment d'une bascule programmée => la commande échoue (voir gestion d'erreurs).

### Côté build / développement
- **Android Studio** (Koala ou plus récent) avec :
  - Android SDK 34 (compileSdk/targetSdk)
  - Kotlin 1.9.24
  - AGP (Android Gradle Plugin) 8.5.2
- `minSdk = 26` (Android 8.0) — `setExactAndAllowWhileIdle` et `AlarmManager` modernes nécessitent au
  minimum cette version pour un comportement fiable.
- Aucune dépendance serveur/cloud : tout tourne en local (device + app), il n'y a rien à héberger.

### Permissions demandées par l'app (et pourquoi)
| Permission | Usage |
|---|---|
| `INTERNET` | Requis techniquement par les sockets UDP même en local |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | Vérifier la connectivité Wi‑Fi avant d'agir |
| `CHANGE_WIFI_MULTICAST_STATE` | Recevoir les réponses au broadcast UDP |
| `ACCESS_FINE_LOCATION` | Nécessaire sur certaines versions Android pour lire le SSID courant (aide au diagnostic "êtes-vous sur le bon Wi‑Fi ?") |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Déclencher les bascules à l'heure précise (Android 12+) |
| `RECEIVE_BOOT_COMPLETED` | Reprogrammer les alarmes après un redémarrage du téléphone |
| `POST_NOTIFICATIONS` | Alerter l'utilisateur en cas d'échec d'une bascule (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Demander explicitement (via un bouton dans l'UI, pas en silencieux) l'exemption Doze pour fiabiliser les alarmes |

---

## Sécurité — points essentiels

1. **Réseau local de confiance obligatoire.** Le protocole Marstek n'a ni authentification ni
   chiffrement. Ne redirigez **jamais** le port UDP de la batterie vers Internet (pas de port
   forwarding, pas d'exposition DMZ). N'utilisez pas de VPN "grand public" qui relaierait ce trafic
   vers un tiers.
2. **Wi‑Fi domestique sécurisé.** Utilisez WPA2/WPA3 avec un mot de passe fort. Évitez de laisser des
   appareils invités sur le même réseau que vos batteries : tout appareil sur le LAN peut
   potentiellement envoyer les mêmes commandes (aucune authentification côté device).
3. **`usesCleartextTraffic` limité explicitement.** Le `network_security_config.xml` interdit tout
   trafic HTTP/HTTPS en clair par défaut ; seul le canal UDP brut (qui n'est pas concerné par cette
   restriction Android, propre à HTTP) est utilisé, et uniquement vers l'IP locale que vous avez
   choisie/découverte — aucune télémétrie, aucun serveur tiers.
4. **Aucune donnée envoyée hors de l'appareil.** L'app ne contacte aucun serveur cloud : les horaires
   sont stockés uniquement en local (DataStore chiffré par défaut au niveau du bac à sable Android).
   Il n'y a pas de compte, pas de tracking, pas d'analytics.
5. **Confirmation utilisateur pour les permissions sensibles.** L'exemption de batterie et
   l'autorisation d'alarmes exactes sont demandées via des boutons explicites dans l'UI (bandeaux
   d'avertissement), jamais déclenchées automatiquement sans action de l'utilisateur.
6. **Échecs visibles, pas silencieux.** Toute commande UDP qui échoue (timeout, device injoignable)
   déclenche une notification locale — pas de retry caché qui pourrait masquer un problème durable
   (ex : batterie hors ligne plusieurs jours).
7. **Validez le sens de `power` avant un usage sans surveillance.** Testez une bascule manuelle avec
   une faible puissance et vérifiez le comportement réel via `ES.GetStatus` avant de vous fier à des
   bascules automatiques nocturnes non supervisées.

---

## Architecture

```
MainActivity.kt        UI Compose : découverte, liste des batteries, édition des horaires
MarstekUdpClient.kt     Client JSON-RPC/UDP (send, discoverDevices, setManualMode, setAutoMode, getMode)
DeviceRepository.kt      Persistance locale (DataStore) des batteries + horaires configurés
AlarmScheduler.kt         Programmation des alarmes quotidiennes ("cron" Android)
ScheduleReceiver.kt       Reçoit le déclenchement d'alarme, envoie la commande UDP, se reprogramme
BootReceiver.kt            Reprogramme toutes les alarmes après un redémarrage du téléphone
```

### Pourquoi pas de vraies tâches "cron" ?
Android n'a pas de démon cron accessible aux apps tierces. `AlarmManager.setExactAndAllowWhileIdle`
est l'équivalent recommandé par Google pour des déclenchements à heure fixe qui doivent survivre au
mode Doze. Chaque alarme se **reprogramme elle‑même pour le lendemain** dans `ScheduleReceiver`
après exécution, ce qui simule une récurrence quotidienne fiable.

---

## Build — sans Android Studio, gratuitement via GitHub Actions

Le dossier `.github/workflows/build-apk.yml` compile automatiquement l'APK dans le cloud, sans
rien installer sur votre machine.

1. **Créer un compte GitHub** (gratuit) sur https://github.com si vous n'en avez pas.
2. **Créer un nouveau dépôt** : bouton "New repository" → nommez-le par ex. `marstek-scheduler` →
   laissez-le public (build illimité gratuit) ou privé (2000 minutes/mois gratuites, largement
   suffisant) → "Create repository".
3. **Envoyer les fichiers du projet** dans ce dépôt. Le plus simple sans ligne de commande :
   - Sur la page du dépôt vide, cliquez "uploading an existing file".
   - Glissez-déposez **tout le contenu** du dossier `MarstekScheduler/` (en conservant
     l'arborescence — GitHub accepte le glisser-déposer de dossiers entiers depuis l'explorateur
     de fichiers de votre PC/Mac dans la plupart des navigateurs récents).
   - Committez sur la branche `main`.
4. **Le build se lance automatiquement.** Allez dans l'onglet **"Actions"** du dépôt : vous verrez
   le workflow "Build APK" en cours d'exécution (2-4 minutes environ).
5. **Télécharger l'APK.** Une fois le run terminé (coche verte), cliquez dessus, puis en bas de la
   page dans "Artifacts", téléchargez `marstek-scheduler-debug-apk` (fichier `.zip` contenant
   l'APK).
6. **Installer sur le téléphone.** Transférez l'APK sur votre Android (mail, Drive, câble USB...),
   ouvrez-le, autorisez "Installer des apps inconnues" pour la source utilisée si demandé, installez.

Aucune clé API, aucun compte développeur Google requis pour ce build "debug" — c'est suffisant pour
un usage personnel sur vos propres appareils. Le service GitHub Actions est gratuit dans les
limites ci-dessus ; aucune carte bancaire n'est demandée pour ce niveau d'usage.

### Alternative sans compte GitHub
D'autres services cloud gratuits existent (ex. Codemagic, offre gratuite limitée en minutes/mois)
mais nécessitent généralement de connecter un dépôt Git de toute façon. GitHub Actions reste
l'option la plus simple et la plus documentée pour ce cas d'usage.

### Si vous changez le code plus tard
Chaque nouvel envoi ("commit") sur la branche `main` relance automatiquement le build et produit un
nouvel artefact APK — pas besoin de relancer quoi que ce soit manuellement.

---

## Limitations connues / TODO

- Pas de retry automatique en cas de perte de paquet UDP (l'UDP n'est pas garanti) — actuellement un
  seul essai, puis notification d'échec. Un retry (2‑3 tentatives avec backoff court) serait une
  amélioration simple à ajouter dans `MarstekUdpClient.send`.
- Le `week_set` (jours actifs en mode Manuel) est fixé à `127` (tous les jours) dans l'UI actuelle ;
  l'ajout de cases à cocher par jour est trivial à faire dans `DeviceCard`.
- Pas de vérification post‑bascule (relecture de `ES.GetMode` pour confirmer que le mode a bien
  changé) — actuellement on se fie au `set_result` renvoyé immédiatement par le device.
