# Alexa Cleaner

Application Android (Kotlin / Jetpack Compose) qui supprime en masse les appareils
connectés **fantômes** ou **hors ligne** d'un compte Alexa : ceux qui restent listés
dans l'onglet « Appareils » de l'application Alexa alors que la skill, le hub ou l'objet
n'existe plus, et qu'il faut normalement supprimer un par un.

> **Avertissement.** Amazon ne fournit aucune API publique pour gérer ces appareils.
> L'application utilise l'API privée du site `alexa.amazon.*`, la même que l'application
> Alexa officielle et que les outils communautaires (alexa-remote-control, alexa-cookie2,
> alexapy). Amazon peut la modifier ou la bloquer à tout moment, et son usage automatisé
> n'est pas couvert par les conditions d'utilisation d'Amazon. Une suppression est
> définitive. Utilisation à vos risques.

## Fonctionnalités

**Connexion**
- Choix du marketplace (amazon.fr, .com, .de, .co.uk, .it, .es, .ca, .com.au, .co.jp, …).
- Connexion sur la page officielle Amazon dans une WebView ; le mot de passe n'est jamais lu
  par l'application. Le flux OAuth de l'application Alexa (PKCE + enregistrement d'appareil)
  fournit un *refresh token* stocké chiffré ; les cookies sont renouvelés automatiquement.
- La page de connexion est ouverte sur `www.amazon.com` quel que soit le marketplace (compte
  Amazon global, même technique qu'alexa-cookie2), puis les cookies du domaine régional
  (`.amazon.fr`, …) sont obtenus à partir du refresh token. Un interrupteur permet d'ouvrir
  la page sur le domaine régional si ce mode échoue.

**Liste des appareils connectés**
- Statut en ligne / hors ligne / inconnu, source (skill, hub ou fabricant), type, activé ou non.
- Recherche texte (nom, fabricant, type, identifiant).
- Filtres : statut, sources, types, désactivés, **doublons** (même nom, on garde la copie
  joignable), **sources entièrement hors ligne** (skill probablement supprimée),
  **hors ligne depuis N jours / N analyses consécutives**.
- Tri : nom, source, type, statut, durée hors ligne, date d'ajout, ordre inverse.
- Sélection : tout ce qui est affiché, inverser, aucun, ou un par un ; fiche détaillée avec
  le JSON brut d'Amazon.
- Onglet Echo : liste en lecture seule des Echo / Fire TV enregistrés et de leur statut.

**Méthodes de suppression**
1. **Un par un** (recommandé) : appels séquentiels avec pause réglable, nouvelles tentatives
   avec backoff exponentiel, ralentissement automatique en cas de limite de débit (HTTP 429),
   arrêt de sécurité après N échecs consécutifs.
2. **En parallèle** : 2 à 8 suppressions simultanées.
3. **Tout oublier puis redécouvrir** : équivalent de « Oublier tous les appareils »
   (`DELETE /api/phoenix`) puis relance de la découverte. Le plus rapide contre les
   fantômes, mais **tous** les appareils sont supprimés : groupes, noms personnalisés et
   routines liées sont perdus.
- **Simulation** (aucune suppression réelle), **sauvegarde JSON** automatique avant chaque
  suppression, confirmation en saisissant le nombre d'appareils.
- La suppression s'exécute dans un job en premier plan (WorkManager) avec notification de
  progression : elle continue si vous quittez l'application.

**Automatisation**
- **Analyses planifiées** (6 h à 1 semaine) qui construisent un historique local :
  Amazon n'expose pas de date de dernière connexion fiable, l'application mesure donc
  elle-même depuis combien de temps chaque appareil est hors ligne.
- **Suppression automatique** selon des règles : N analyses consécutives hors ligne,
  N jours hors ligne, sources concernées, types exclus, seulement si toute la source est
  hors ligne, maximum par exécution. Mode simulation par défaut (notification de ce qui
  aurait été supprimé).

**Traçabilité**
- Journal de chaque suppression (résultat, message, nombre de tentatives).
- Export JSON / CSV de la liste, partage ou enregistrement dans Téléchargements.
- Journal technique des appels HTTP dans les réglages.

## Limites connues

- **Désinscription des Echo** : non prise en charge. Elle passe par une autre API
  (« Gérer votre contenu et vos appareils ») ; l'onglet Echo est en lecture seule.
- **Durée hors ligne** : elle n'est connue qu'à partir des analyses de l'application.
  Au premier lancement, tous les appareils hors ligne apparaissent « hors ligne depuis 0 j ».
- **Structure de l'API** : le parseur parcourt tout le JSON de `/api/phoenix` sans dépendre
  d'un chemin exact, mais un changement de format côté Amazon peut casser la liste ou la
  suppression. Le journal technique aide à diagnostiquer.
- **Authentification** : Amazon peut demander une vérification (OTP, captcha) dans la
  WebView ; c'est normal. Le flux n'a pas pu être testé contre les serveurs Amazon
  depuis l'environnement de développement : voir « État de la vérification ».

## Compilation

Prérequis : JDK 17, Android SDK (API 35). Puis :

```bash
./gradlew :core:test           # tests unitaires de la logique métier
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Le workflow GitHub Actions (`.github/workflows/android.yml`) exécute les tests et publie
les APK debug et release en artefact à chaque push. La release est signée avec la clé de
debug : remplacez `signingConfig` dans `app/build.gradle.kts` avant toute distribution.

## Architecture

```
core/   Kotlin pur, sans dépendance Android, testé unitairement
  auth/     AmazonAuth (PKCE, /auth/register, échange refresh token → cookies), SessionManager (CSRF, renouvellement)
  api/      AlexaApi (phoenix, devices-v2, DELETE appliance, DELETE phoenix, discovery), PhoenixParser
  domain/   DeviceFilters (filtres, tri, doublons, sources mortes), History (analyses), PurgeEngine (méthodes, retries), Settings
  data/     Dépôts JSON (historique, réglages, snapshot, journal)
app/    Android : WebView de connexion, stockage chiffré, WorkManager (ScanWorker, PurgeWorker), UI Compose
```

## Endpoints utilisés

| Usage | Requête |
|---|---|
| Connexion | `GET https://www.amazon.com/ap/signin?...openid.oa2.code_challenge=...` puis capture de `/ap/maplanding?openid.oa2.authorization_code=` |
| Enregistrement | `POST https://api.amazon.com/auth/register` |
| Cookies régionaux | `POST https://www.amazon.<tld>/ap/exchangetoken/cookies` |
| CSRF | `GET https://alexa.amazon.<tld>/api/language` (cookie `csrf`) |
| Appareils connectés | `GET https://alexa.amazon.<tld>/api/phoenix` |
| Echo | `GET https://alexa.amazon.<tld>/api/devices-v2/device?cached=false` |
| Supprimer un appareil | `DELETE https://alexa.amazon.<tld>/api/phoenix/appliance/{applianceId}` |
| Tout oublier | `DELETE https://alexa.amazon.<tld>/api/phoenix` |
| Redécouvrir | `POST https://alexa.amazon.<tld>/api/phoenix/discovery` |

## État de la vérification

- Module `core` : compilé et testé (21 tests : parseur, filtres, historique, règles
  d'auto-suppression, moteur de purge, flux d'authentification contre un serveur HTTP simulé).
- Module `app` : compilé par GitHub Actions (aucun SDK Android dans l'environnement de
  développement).
- **Non vérifié** : le comportement réel des serveurs Amazon (connexion, format exact des
  réponses, codes d'erreur). Les endpoints et paramètres sont ceux utilisés par les projets
  communautaires cités ; testez d'abord en mode simulation et sur une petite sélection.
