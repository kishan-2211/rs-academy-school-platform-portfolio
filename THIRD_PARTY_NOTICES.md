# Third-party notices

Original EduCore code, product design, R.S. Academy identity, and school assets remain subject to `NOTICE.md`. The following third-party motion assets retain their own terms.

## LottieFiles animations

| Local asset | Work | Creator | Source | Terms |
| --- | --- | --- | --- | --- |
| `apps/web/public/animations/study-discussion.lottie` | Study discussion | Md. Imam Hossain | https://lottiefiles.com/free-animation/study-discussion-HfaDECsT9H | Lottie Simple License (FL 9.13.21) |
| `apps/web/public/animations/successful-target.lottie` | Successful target | Md. Imam Hossain | https://lottiefiles.com/free-animation/successful-target-wvXfLnnZLn | Lottie Simple License (FL 9.13.21) |

The assets were retrieved from the official LottieFiles asset host on 2026-08-20. Each source page was individually verified as “Free to use under the Lottie Simple License.” The files are bundled only as part of the product and must not be redistributed or resold as a standalone animation collection.

The applicable license text is retained at `apps/web/public/animations/LICENSE.lottiefiles.txt` and is also available at https://lottiefiles.com/page/license.

## dotLottie player

`@lottiefiles/dotlottie-react` and its `@lottiefiles/dotlottie-web` dependency are provided by LottieFiles under the MIT License. The locally served `dotlottie-player.wasm` is the matching runtime engine from `@lottiefiles/dotlottie-web` 0.79.0.

The complete player license is bundled at `apps/web/public/animations/LICENSE.dotlottie-player.txt`.

## Server-side Web Push

`com.interaso:webpush` 1.3.0 is published by Interactive Solutions s.r.o. under the MIT License. Source and license: https://github.com/interaso/webpush and https://github.com/interaso/webpush/blob/main/LICENSE. It supplies RFC 8291 encryption and VAPID header construction; EduCore retains its own outbound HTTP policy and delivery evidence.

The resolved dependency graph includes Kotlin standard library 2.3.21 (managed by the existing Spring Boot parent) and JetBrains annotations 13.0. These remain subject to their distributed license notices. No third-party cryptographic implementation is copied into production source.

The complete Web Push MIT notice is bundled in the API at `META-INF/licenses/webpush-LICENSE.txt` (source: `apps/api/src/main/resources/META-INF/licenses/webpush-LICENSE.txt`).
