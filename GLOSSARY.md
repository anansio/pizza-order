# Pizza Order

Bestell-App für Pizza-Abende: Gäste bestellen am Tablet, die Küche arbeitet die Bestellungen ab.

## Bestellen

**Gast**:
Die Person, die eine Pizza bestellt — am Tablet oder vom eigenen Handy; erscheint mit Namen auf dem Ausdruck.
_Avoid_: Kunde, Benutzer

**Bestellung**:
Eine Pizza für einen Gast, mit fortlaufender Nummer, Uhrzeit und optionaler Notiz für die Küche.
_Avoid_: Auftrag, Order

**Eigenkreation**:
Eine Pizza, deren Sauce und Beläge der Gast selbst wählt, im Gegensatz zu einer vordefinierten Pizza aus dem Menü.
_Avoid_: Custom-Pizza

**Teig-Bestand**:
Wie viele vorbereitete Teiglinge noch übrig sind; jede Bestellung verbraucht einen, bei 0 ist ausverkauft. Die Anzahl für heute wird im Admin-Panel eingetragen; „Alle Bestellungen löschen“ füllt den Bestand wieder darauf auf.
_Avoid_: Lager, Stock

**Menü**:
Die Saucen, Beläge und vordefinierten Pizzen, aus denen Gäste wählen können.
_Avoid_: Speisekarte, Konfiguration

**Heute aus**:
Eine Zutat, die für heute ausgegangen ist: Sie bleibt im Menü, aber Gäste können sie und vordefinierte Pizzen damit nicht wählen. „Alle Bestellungen löschen“ hebt das auf.
_Avoid_: Ausverkauft (das sagt der Teig-Bestand)

**Offene Bestellungen**:
Die Bestellungen, die noch nicht fertig sind. Das Limit dafür steht im Admin-Panel unter „Annahme“: Ist es erreicht, lehnt die App weitere Bestellungen mit einer Meldung ab, statt sie anzunehmen. Ohne Eintrag gibt es kein Limit; der Teig-Bestand bleibt die harte Grenze.
_Avoid_: Warteschlange (die ist die Reihenfolge, nicht die Grenze)

**Allergen**:
Ein Stoff, den eine Zutat enthält und auf den Gäste reagieren können (derzeit Laktose, Nüsse, Gluten und Soja). Wird je Zutat im Admin-Panel gepflegt; eine Pizza enthält alle Allergene ihrer Zutaten.
_Avoid_: Unverträglichkeit, Allergie

**Standard-Menü**:
Das mitgelieferte Menü, das nach „Alles zurücksetzen“ im Admin-Panel wieder gilt.

## Küche

**Bestellliste**:
Die Übersicht aller Bestellungen, nach Status gruppiert (Tab „Bestellungen“).
_Avoid_: Queue

**Warteschlange**:
Die offenen Bestellungen in der Reihenfolge ihres Eingangs, also alles außer „Fertig“. Daraus ergibt sich die Position der Gäste („noch 2 vor dir“); die Zahl wird abgeleitet und nie gespeichert.
_Avoid_: Queue

**Wartet / Im Ofen / Fertig**:
Der Zustand einer Bestellung, abgeleitet aus den Zeitstempeln, die die Küche mit einem Tippen setzt.
_Avoid_: Offen, Pending, erledigt

## Drucken

**Ausdruck**:
Das Stück Papier, das der Thermodrucker zu einer Bestellung ausgibt.
_Avoid_: Bon, Beleg, Zettel (Beleg ist der Name eines Ausdruck-Designs, kein Oberbegriff)

**Ausdruck-Design**:
Das Aussehen aller Ausdrucke, „Beleg“ oder „Beleg Design“; gilt für alle Bestellungen gleichzeitig.
_Avoid_: Druckstil, Vorlage

**QR-Link**:
Eine URL, die als QR-Code auf den Küchenbon gedruckt wird; die Bezeichnung dazu ist nur im Admin-Panel sichtbar. Im Panel heißt der Tab deshalb „QR-Links zum Bon“.
_Avoid_: Fun-Link

**Gäste-QR-Code**:
Der QR-Code, der die Gastseite im lokalen Netz öffnet. Steht im Schnellmenü unter „QR-Code für Gäste“ und im Gäste-Abschnitt des Admin-Panels; er ist kein QR-Link und landet nicht auf dem Küchenbon.
_Avoid_: Bestell-QR, Gast-Link, QR-Links

**QR-Bon**:
Der gedruckte Bon für den Tisch: derselbe Gäste-QR-Code, dazu Adresse und Code in Schrift, für Handys, deren Kamera nicht will. Damit kann das Tablet in der Küche bleiben.
_Avoid_: Tischkarte, Flyer, QR-Karte

## Verwaltung

**Admin-Panel**:
Der per Muster gesperrte Bereich, in dem Menü, Ausdruck-Design und QR-Links bearbeitet werden.
_Avoid_: Einstellungen, Settings
