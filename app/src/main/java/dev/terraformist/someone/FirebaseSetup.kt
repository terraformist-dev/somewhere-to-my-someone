package dev.terraformist.someone

/**
 * The owner's Firebase project. Both values come from the Firebase console:
 *  - API_KEY: Project settings (gear) → General → "Web API key".
 *  - DATABASE_URL: Realtime Database → Data, the link at the top (ends in firebasedatabase.app).
 *
 * These aren't secrets: Firebase expects them to ship inside apps. What keeps the data private
 * is the rules in `firebase-rules.json`, and the fact that every location is scrambled on the
 * phone before it's sent. Until both are filled in, the app says it isn't connected yet.
 */
object FirebaseSetup {
    const val API_KEY = ""
    const val DATABASE_URL = ""
}
