/* CrisisFlow geocoding helpers: place name -> latitude/longitude (OpenStreetMap Nominatim) */
const NOMINATIM = "https://nominatim.openstreetmap.org";

async function searchPlaces(query, limit = 5) {
    const url = `${NOMINATIM}/search?format=jsonv2&addressdetails=0&limit=${limit}&q=${encodeURIComponent(query)}`;
    const res = await fetch(url, { headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error("Place search failed (" + res.status + ")");
    const data = await res.json();
    return data.map(p => ({
        name: p.display_name,
        lat: Number(p.lat),
        lng: Number(p.lon)
    }));
}

async function reversePlace(lat, lng) {
    const url = `${NOMINATIM}/reverse?format=jsonv2&lat=${lat}&lon=${lng}`;
    const res = await fetch(url, { headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error("Reverse lookup failed");
    const data = await res.json();
    return data.display_name || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

/**
 * Turn a text input into a place-name search box with a suggestion dropdown.
 * Picking a suggestion fills the latitude/longitude inputs automatically.
 * Returns { resolve(), setPoint(lat,lng,name) } so forms can use it on submit.
 */
function attachPlaceSearch(input, latInput, lngInput, statusEl, onPick) {
    const box = document.createElement("div");
    box.className = "suggest-box";
    box.hidden = true;
    input.parentElement.style.position = "relative";
    input.parentElement.appendChild(box);
    input.setAttribute("autocomplete", "off");

    let timer = null, seq = 0;

    function setStatus(text, kind) {
        if (!statusEl) return;
        statusEl.textContent = text;
        statusEl.dataset.kind = kind || "";
    }

    function setPoint(lat, lng, name) {
        latInput.value = Number(lat).toFixed(6);
        lngInput.value = Number(lng).toFixed(6);
        if (name) input.value = name;
        box.hidden = true;
        setStatus(`📍 ${Number(lat).toFixed(4)}, ${Number(lng).toFixed(4)}`, "ok");
        if (onPick) onPick(Number(lat), Number(lng), name);
    }

    function render(results) {
        box.innerHTML = "";
        if (!results.length) {
            box.innerHTML = '<div class="suggest-empty">No matching place found</div>';
            box.hidden = false;
            return;
        }
        results.forEach(r => {
            const item = document.createElement("button");
            item.type = "button";
            item.className = "suggest-item";
            item.textContent = r.name;
            item.addEventListener("mousedown", e => {
                e.preventDefault();
                setPoint(r.lat, r.lng, r.name);
            });
            box.appendChild(item);
        });
        box.hidden = false;
    }

    input.addEventListener("input", () => {
        latInput.value = "";
        lngInput.value = "";
        clearTimeout(timer);
        const q = input.value.trim();
        if (q.length < 3) { box.hidden = true; setStatus("", ""); return; }
        setStatus("Searching places…", "busy");
        const mine = ++seq;
        timer = setTimeout(async () => {
            try {
                const results = await searchPlaces(q);
                if (mine !== seq) return;
                setStatus(results.length ? "Pick a suggestion below" : "No place found", results.length ? "" : "err");
                render(results);
            } catch (e) {
                setStatus(e.message, "err");
            }
        }, 450);
    });

    input.addEventListener("blur", () => setTimeout(() => (box.hidden = true), 150));

    return {
        setPoint,
        /* If the user typed a name but didn't pick a suggestion, use the best match. */
        async resolve() {
            if (latInput.value && lngInput.value) return true;
            const q = input.value.trim();
            if (!q) return false;
            setStatus("Finding place…", "busy");
            const results = await searchPlaces(q, 1);
            if (!results.length) { setStatus("Place not found – try a more specific name", "err"); return false; }
            setPoint(results[0].lat, results[0].lng, null);
            return true;
        }
    };
}
