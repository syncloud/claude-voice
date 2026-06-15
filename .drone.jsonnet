local build() = {
    kind: "pipeline",
    name: "build",

    platform: {
        os: "linux",
        arch: "amd64"
    },
    steps: [
        {
            name: "bridge",
            image: "golang:1.23-bookworm",
            commands: [
                "./bridge/build.sh"
            ]
        },
        {
            name: "android",
            image: "runmymind/docker-android-sdk:ubuntu-standalone-20240812",
            environment: {
                KEY_STORE: { from_secret: "KEY_STORE" },
                ANDROID_STORE_FILE: { from_secret: "ANDROID_STORE_FILE" },
                ANDROID_STORE_PASSWORD: { from_secret: "ANDROID_STORE_PASSWORD" },
                ANDROID_KEY_ALIAS: { from_secret: "ANDROID_KEY_ALIAS" },
                ANDROID_KEY_PASSWORD: { from_secret: "ANDROID_KEY_PASSWORD" },
            },
            commands: [
                "./android/build.sh"
            ]
        },
        {
            name: "publish to github",
            image: "plugins/github-release:1.0.0",
            settings: {
                api_key: { from_secret: "github_token" },
                files: [ "claude-voice-*.apk", "claude-voice-bridge-arm64" ],
                title: "${DRONE_TAG}",
                note: "RELEASE_NOTES.md",
                overwrite: true,
                file_exists: "overwrite"
            },
            when: {
                event: [ "tag" ]
            }
        },
        {
            name: "artifacts",
            image: "appleboy/drone-scp",
            settings: {
                host: { from_secret: "artifact_host" },
                username: "artifact",
                key: { from_secret: "artifact_key" },
                timeout: "2m",
                command_timeout: "2m",
                target: "/home/artifact/repo/claude-voice/${DRONE_BUILD_NUMBER}",
                source: [ "claude-voice-*.apk", "claude-voice-bridge-arm64" ],
                strip_components: 0
            },
            when: {
                status: [ "failure", "success" ]
            }
        }
    ]
};

[
    build()
]
