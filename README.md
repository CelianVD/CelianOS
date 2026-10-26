# CélianOS

Application Android personnelle (Kotlin + Jetpack Compose) qui ajoute trois modules façon « OS » par-dessus Android : une **Dynamic Island** flottante, un **Spotlight** de recherche globale avec assistant, et un **Glow Dock** pour transformer le téléphone en lecteur de bureau relié au PC.

> [!WARNING]
> Projet personnel, en cours de développement (v0.5). Le code est publié tel quel, sans garantie.

## Fonctionnalités

### Dynamic Island
- Pilule flottante au-dessus de la barre d'état, affichée via un service d'accessibilité.
- Lecture en cours (titre, pochette, pause/lecture) grâce à l'écoute des notifications média.
- Apparence réglable, avec aperçu en direct depuis les réglages.

### Spotlight
- Recherche globale dans une fenêtre translucide, déclenchée par un bord tactile de l'écran, un double toucher sur la barre de navigation, ou une tuile des réglages rapides.
- Recherche d'applis, de contacts, de réglages, de photos, vidéos et documents (par titre).
- Définitions (Wiktionnaire) et résumés (Wikipédia).
- Widgets : météo (Open-Meteo, sans clé), batteries (téléphone et appareils Bluetooth), agenda, habitudes, notes rapides, citation du jour.
- Moteurs de recherche et raccourcis web configurables (YouTube, Maps, Spotify, GitHub, etc.).
- **Assistant à commandes** : un routeur local instantané et hors ligne traite d'abord la saisie ; si rien ne correspond, une IA prend le relais avec appel de fonctions. Les actions passent par des *connecteurs* activables séparément : minuteur et réveil, notes, agenda, messages et contacts, modules de l'app.
- Garde-fou : les actions visibles par une autre personne (ex. envoyer un message) sont signalées comme telles et demandent confirmation en cas d'ambiguïté.

### Glow Dock
- Lecteur plein écran pour téléphone posé sur un support, avec halo lumineux qui réagit au son (basses, médiums, aigus) et couleurs tirées de la pochette.
- Lancement automatique quand une liaison avec l'ordinateur est détectée : Lien avec Windows (Link to Windows), câble USB ou agent PC.
- Options : anti-burn-in, flou d'arrière-plan, affichage sur l'écran verrouillé, posture déclenchant le dock (paysage, à plat, etc.).
- Avec l'agent PC : volume et muet du PC, musique du PC, lancement d'applis du PC depuis des raccourcis dans le dock.

## Fournisseurs d'IA

L'assistant fonctionne avec **Gemini**, **Mistral** et **OpenAI**. Tu fournis ta propre clé API dans les réglages de Spotlight. La liste des modèles est chargée depuis l'API du fournisseur, avec repli automatique sur un autre fournisseur ou modèle en cas d'erreur (429, 503). L'IA est optionnelle : sans clé, la recherche et les commandes locales restent utilisables.

## Prérequis et compilation

- Android Studio récent, JDK 21
- Android 8.0 ou plus (`minSdk 26`), cible `targetSdk 36`
- Android Gradle Plugin 8.12, Kotlin 2.0.21, Compose BOM 2024.09

```bash
git clone https://github.com/CelianVD/CelianOS.git
cd CelianOS
./gradlew assembleDebug        # Windows : gradlew.bat assembleDebug
```

L'APK de debug se trouve dans `app/build/outputs/apk/debug/`.

## Permissions et autorisations

L'app demande beaucoup d'accès, chacun lié à une fonctionnalité précise. Rien n'est requis tant que le module concerné n'est pas utilisé.

| Autorisation | Utilité |
|---|---|
| Superposition (`SYSTEM_ALERT_WINDOW`) et service d'accessibilité | Afficher la Dynamic Island et le bord tactile Spotlight |
| Accès aux notifications | Détecter la musique en cours et la liaison Windows |
| `RECORD_AUDIO` | Analyser le son sortant pour le halo du Glow Dock |
| `READ_CONTACTS`, `READ_CALENDAR` | Spotlight : contacts, agenda |
| `BLUETOOTH_CONNECT` | Batterie des appareils Bluetooth |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `MANAGE_EXTERNAL_STORAGE` | Recherche de photos, vidéos et documents |
| `PACKAGE_USAGE_STATS` | Applis récentes et habitudes |
| `SET_ALARM` | Minuteur et réveil via l'appli Horloge |
| `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` | Lancement automatique du dock |
| `INTERNET` | IA, météo, Wikipédia, agent PC |

Le service d'accessibilité est déclaré avec `canRetrieveWindowContent="false"` : il ne lit pas le contenu de l'écran.

## Agent PC (optionnel)

Le Glow Dock peut parler à un petit serveur HTTP tournant sur le PC (port par défaut `8765`). L'application le contacte avec un jeton partagé dans l'en-tête `X-Token`. Endpoints utilisés :

`GET /status`, `GET /levels1`, `GET /apps`, `POST /volume`, `POST /media`, `POST /launch`

Dans l'app : renseigne l'IP, le port et le jeton dans les réglages du Glow Dock. Le code de l'agent PC **n'est pas inclus dans ce dépôt**.
> [!NOTE]
> L'application python pour PC n'est pas encore disponible publiquement.


## Sécurité et vie privée

- Les clés API sont chiffrées en AES-GCM avec une clé stockée dans l'Android Keystore (`SecretStore.kt`) : elles ne quittent pas l'appareil.
- Aucune clé ni donnée personnelle n'est présente dans le dépôt. Ne commite jamais de `local.properties`, de keystore (`*.jks`) ni de `google-services.json` : ils sont dans le `.gitignore`.
- La liaison avec l'agent PC utilise HTTP en clair (`usesCleartextTraffic`) : à n'utiliser que sur un réseau de confiance.
- Les recherches envoyées à l'IA ou aux API publiques (Wikipédia, météo) passent par ces services externes.

## Structure du code

```
app/src/main/java/com/example/celianos/
├── MainActivity.kt, HomeScreen.kt          Accueil et navigation entre modules
├── DynamicIsland*.kt, MediaListenerService  Dynamic Island et lecture en cours
├── Spotlight*.kt                            Recherche, widgets, wiki, interface
├── ConnectorCore.kt, Connectors.kt          Assistant à commandes et connecteurs
├── AiRouter.kt, AiProviders.kt              Choix du modèle IA et repli automatique
├── DeskDock*.kt, DockApps.kt                Glow Dock
├── PcAgent.kt, Pclink.kt                    Liaison avec le PC
├── SecretStore.kt, Celianprefs.kt           Chiffrement des clés et préférences
└── Theme.kt, Settings*.kt                   Thème et composants de réglages
```

**Ajouter un module :** une entrée dans `HomeScreen.kt`, une valeur dans l'enum `Screen` (`MainActivity.kt`) et la page de réglages correspondante.
**Ajouter un connecteur :** écrire un `object MonConnecteur : Connector` et l'ajouter à `ConnectorRegistry.builtin`.

## Licence

[MIT](LICENSE) © 2026 Célian
