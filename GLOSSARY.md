# Pizza Order

Bestell-App für Pizza-Abende: Gäste bestellen am Tablet, die Küche arbeitet die Bestellungen ab.

## Bestellen

**Gast**:
Die Person, die am Tablet eine Pizza bestellt; erscheint mit Namen auf dem Ausdruck.
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

**Stornieren**:
Eine offene Bestellung im Admin-Panel zurücknehmen; sie verschwindet aus der Bestellliste, und ihr Teigling kommt zurück in den Teig-Bestand.
_Avoid_: Löschen (das meint „Alle Bestellungen löschen“)

**Menü**:
Die Saucen, Beläge und vordefinierten Pizzen, aus denen Gäste wählen können.
_Avoid_: Speisekarte, Konfiguration

**Heute aus**:
Eine Zutat, die für heute ausgegangen ist: Sie bleibt im Menü, aber Gäste können sie und vordefinierte Pizzen damit nicht wählen. „Alle Bestellungen löschen“ hebt das auf.
_Avoid_: Ausverkauft (das sagt der Teig-Bestand)

**Allergen**:
Ein Stoff, den eine Zutat enthält und auf den Gäste reagieren können (derzeit Laktose und Nüsse). Wird je Zutat im Admin-Panel gepflegt; eine Pizza enthält alle Allergene ihrer Zutaten.
_Avoid_: Unverträglichkeit, Allergie

**Standard-Menü**:
Das mitgelieferte Menü, auf das sich das Menü im Admin-Panel zurücksetzen lässt.

## Küche

**Bestellliste**:
Die Übersicht aller Bestellungen, getrennt nach offen und fertig (Tab „Bestellungen“).
_Avoid_: Warteschlange, Queue

**Offen / Fertig**:
Der Zustand einer Bestellung: offen, bis die Küche sie als fertig markiert.
_Avoid_: Pending, erledigt

## Drucken

**Ausdruck**:
Das Stück Papier, das der Thermodrucker zu einer Bestellung ausgibt.
_Avoid_: Bon, Beleg, Zettel (das sind Namen von Ausdruck-Designs, keine Oberbegriffe)

**Ausdruck-Design**:
Das Aussehen aller Ausdrucke, z. B. „Beleg“, „Bon“ oder „Kompakt“; gilt für alle Bestellungen gleichzeitig.
_Avoid_: Druckstil, Vorlage

**QR-Link**:
Eine URL, die als QR-Code auf den Ausdruck gedruckt wird; die Bezeichnung dazu ist nur im Admin-Panel sichtbar.
_Avoid_: Fun-Link

## Verwaltung

**Admin-Panel**:
Der per Muster gesperrte Bereich, in dem Menü, Ausdruck-Design und QR-Links bearbeitet werden.
_Avoid_: Einstellungen, Settings
