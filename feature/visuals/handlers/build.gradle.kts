// The Visuals feature's handlers (ADR-0005): they follow and ask Customization through its :api only,
// and keep the person's Favourites, Recent and folders in the feature's own DataStore (ADR-0004).
plugins {
    id("geode.feature-handlers")
}

dependencies {
    implementation(project(":feature:customization:api"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
