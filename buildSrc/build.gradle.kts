plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.kover.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("koverAggregation") {
            id = "kover.aggregation"
            implementationClass = "KompaktKoverAggregationPlugin"
        }
    }
}
