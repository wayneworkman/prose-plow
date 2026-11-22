# Store metadata

`metadata/android/en-US/` holds the texts and the icon that F-Droid reads from an app's source
repository. The directories follow the layout of the fastlane tool; the tool itself is not used
(it would replace this file with one of its own), and nothing here takes part in building the
app.

| File | Content | F-Droid's limit |
| --- | --- | --- |
| `title.txt` | The name of the app | 50 characters |
| `short_description.txt` | One line, with no full stop at the end | 80 characters |
| `full_description.txt` | The description. Every line break is shown, so a paragraph is one line. Of HTML only simple tags such as `<b>` are allowed | 4,000 characters |
| `changelogs/<versionCode>.txt` | What is new in a release: one file for each, named after its `versionCode` exactly (`100.txt` for 0.1.0) | 500 characters |
| `images/icon.png` | The icon, 512 x 512 pixels, written by `render_icon.py` | PNG or JPEG |
| `images/phoneScreenshots/` | Screenshots, which are not there yet | PNG or JPEG |

The limits are those of F-Droid's documentation ("All About Descriptions, Graphics, and
Screenshots" and "Submitting to F-Droid Quick Start Guide") as read on 22 November 2026. A text
that is longer is cut off at the limit. When a model is added to the build or removed from it,
change its line in the list of `full_description.txt` and the size of the APK in the paragraph
below that list.

## The icon

`images/icon.png` is rendered from the launcher icon, not drawn. `render_icon.py` reads the
adaptive icon (`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`), finds the background
colour and the vector foreground that it names, and renders the centre 72 dp of the two 108 dp
layers, which is the square a launcher shows, at 512 x 512 pixels without a launcher's mask. It
refuses a drawable that uses something it does not translate. Run it again whenever one of
those resources changes. It needs Python 3.10 or later and one package, resvg-py 0.5.0 (which
contains resvg 0.48.1), in a virtualenv of its own outside the repository:

```sh
python3 -m venv "$HOME/proseplow/icon-venv"
"$HOME/proseplow/icon-venv/bin/pip" install resvg-py==0.5.0
"$HOME/proseplow/icon-venv/bin/python" fastlane/render_icon.py            # writes the icon
"$HOME/proseplow/icon-venv/bin/python" fastlane/render_icon.py --check    # compares, writes nothing
```

`--check` fails when the file in the repository is not what the script renders.

The bytes of the PNG depend on the renderer, so the script refuses any other version of the
package. With the same resources it wrote the same bytes on every run on Ubuntu 24.04 (x86_64,
Python 3.12.3); whether another processor architecture gives the same bytes has not been
tried.

## Screenshots

Screenshots are taken by hand and are not in the repository yet. F-Droid reads them from
`metadata/android/en-US/images/phoneScreenshots/`, where its documentation names them `1.png`,
`2.png` and so on: PNG or JPEG files with the ending `png`, `jpg` or `jpeg`, listed in the order
of their names. It prescribes no size in pixels and no aspect ratio, so use the phone's own
resolution; it removes the metadata of every image and may compress it again. The app blocks
screenshots: take them during "Allow screenshots for 10 minutes" (Settings, on Android 13 or
later), of chats that hold nothing private. F-Droid shows an app on its "Latest" tab only when
it has at least one screenshot or a feature graphic (`images/featureGraphic.png`, optional and
not provided).
