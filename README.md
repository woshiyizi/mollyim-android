# Molly

[![Android CI](https://github.com/mollyim/mollyim-android/workflows/Android%20CI/badge.svg)](https://github.com/mollyim/mollyim-android/actions)
[![Total alerts](https://img.shields.io/lgtm/alerts/g/mollyim/mollyim-android.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/mollyim/mollyim-android/alerts/)
[![Translation status](https://hosted.weblate.org/widgets/molly-instant-messenger/-/svg-badge.svg)](https://hosted.weblate.org/engage/molly-instant-messenger/?utm_source=widget)

> Molly is a hardened version of [Signal](https://github.com/signalapp/Signal-Android) for Android, the fast simple yet secure messaging app by [Signal Foundation](https://signal.org).

## Introduction

Back in 2018, Signal allowed the user to set a passphrase to secure the local message database. But this option was removed with the introduction of full-disk encryption on Android. Molly brings it back again with additional security features.

Molly is updated every two weeks to include the latest Signal changes and bug fixes.

## Download

You can download the app from GitHub's [Releases](https://github.com/mollyim/mollyim-android/releases/latest) page or install it from F-Droid.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://molly.im/fdroid/)

## Features

Molly has unique features compared to Signal:

- Protects database with [passphrase encryption](https://github.com/mollyim/mollyim-android/wiki/Data-Encryption-At-Rest)
- Locks down the app automatically after you go a certain time without unlocking your device
- Securely shreds sensitive data from RAM
- Logging debug messages can be disabled

Besides that, you will find all the features of Signal plus some minor tweaks and improvements. The only exception is the SMS integration, that was removed for compatibility reasons.

## Compatibility with Signal

Molly and Signal can be installed on the same device. If you need a 2nd number to chat, you can use Molly along with Signal.

However, you cannot use the same phone number on both apps at the same time. Only the last app to register will remain active, and the other will go offline. To solve this, remember that you are not limited to use only your main phone number, but also any number on you can receive SMS or phone calls at registration.

## Backups

Backups are fully compatible. Signal [backups](https://support.signal.org/hc/en-us/articles/360007059752-Backup-and-Restore-Messages) can be restored in Molly and the other way around. To do that, simply rename the backup file and copy it into the expected path, so the app can find the backup to restore during installation.

The path within internal storage where backups are written by Signal:
- `Signal/Backups/Signal-year-month-date-time.backup`

And Molly:
- `Molly/Backups/Molly-year-month-date-time.backup`

## Feedback

- Ask a question on the forum [community.signalusers.org](https://community.signalusers.org/)
- [Submit bugs and feature requests](https://github.com/mollyim/mollyim-android/issues)

## Reproducible Builds

Molly supports reproducible builds, so that anyone can run the build process again and reproduce the same APK as the original release.

Please read the [Reproducible Builds](https://github.com/mollyim/mollyim-android/wiki/Reproducible-Builds) page in our [wiki](https://github.com/mollyim/mollyim-android/wiki).

## Changelog

See the [Changelog](https://github.com/mollyim/mollyim-android/wiki/Changelog) to view recent changes.

## License

License and legal notices in the original [README](README-ORIG.md).

## Disclaimer

This project is *NOT* sponsored by Signal Messenger LLC or Signal Foundation.

The software is produced independently of Signal and carries no guarantee about quality, security or anything else. Use at your own risk.
