Esta copia local del proyecto incluye libndi.so oficial del SDK NDI para Android
en arm64-v8a/ y x86_64/. Los archivos .so se excluyen de Git por .gitignore.
Las licencias se conservan en licenses/ndi/ en la raíz del proyecto.

Para el Samsung A51: colocar libndi.so oficial para Android ARM64 en arm64-v8a/libndi.so.
No usar una biblioteca Linux ARM64: no es compatible con Android.
Conservar aquí también las dependencias .so que el SDK Android indique.
Para un emulador x86_64, usar las bibliotecas Android de esa arquitectura en x86_64/.

Luego recompilar. Sin esta biblioteca la cámara y OSC funcionan, pero el botón
de transmisión NDI informa que falta el SDK: nunca simula una transmisión.
