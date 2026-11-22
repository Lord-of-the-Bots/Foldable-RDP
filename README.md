# Foldable RDP

Foldable RDP brings together the things I liked in different Android RDP clients. I manage large networks of PCs and servers for work. For years I've spent four to eight hours a day using RDP from my phone. With this release I wanted to keep what worked over those years and leave out the things I never used or found awkward.

I hadn't seen many of the features below in any Android RDP client. When a client did have something similar, it often worked poorly. After a few hours those details could make working from a phone painful. I wanted RDP to be comfortable on a phone in all the situations I run into at work.

## Display

The Windows desktop uses the pixel dimensions of the session window, so text stays sharp. Windows display scaling can be adjusted during the session. Pinch to zoom is available as well. Two-finger vertical and horizontal scrolling takes priority over zoom.

The client respects Android's auto-rotate setting. When the screen rotates, it requests a new resolution from Windows. Changing resolution in an open session requires RDP Display Control on the host.

A small panel at the edge of the screen opens the keyboard, switches between computers and gives access to the session settings, including Windows scaling, input mode and pointer speed.

## Foldable phones

Unfolding the phone, folding it shut, rotating the screen or resizing the app window changes the resolution requested from Windows. The desktop can use the space available without stretching the previous image. Windows scaling is saved separately for the cover and inner displays. Opening another app alongside the session changes the desktop size while keeping the scale for that display. The same display settings apply when another computer is added.

## Controls

I've always preferred a touchpad for speed and precision. That's the mode I've spent the most time refining. The cover and inner displays have separate pointer speeds, both adjustable from the session panel.

Text entry uses the phone's regular keyboard with an extra row of frequently used Windows keys. A second layout has the full set of function keys and navigation keys. One tap switches between the two.

If opening the keyboard would hide the pointer, the desktop moves up to keep it visible. The desktop can still be panned up and down while typing. Moving the pointer to the edge brings the hidden area into view. Closing the keyboard lets the desktop fill the window again.

## Multiple computers

Several computers can stay connected at once. Switching to another desktop keeps the other sessions open. The client keeps idle sessions connected as long as Android and the host allow. If a connection drops while idle or after a network change, the client tries to reconnect.

## Files and copying

Phone storage is shared with each new connection by default. It appears as a redirected drive in Windows Explorer and can be used to copy files in either direction. Android must grant storage access and the host must allow drive redirection.

The clipboard works across computers. A file copied on one PC can be pasted on another PC or into a folder in the phone's file manager. Files copied on the phone can also be pasted into Windows Explorer. On Android this requires a file manager that supports files on the system clipboard. When notifications are allowed, the notification shade shows progress for transfers to or from the phone.

Text can also be copied between PCs and between a PC and the phone.

## About the project

I spend a good part of every working day managing computers over RDP. If something in the client slows that work down, I run into it myself. I'll keep fixing those problems and making the controls easier to use.

Foldable RDP is based on [aRDP by Iordan Iordanov and contributors](https://github.com/iiordanov/remote-desktop-clients) and uses [FreeRDP](https://www.freerdp.com/).

[Download the Android APK](https://github.com/Lord-of-the-Bots/Foldable-RDP/releases/latest) · [Build from source](BUILDING.md)
