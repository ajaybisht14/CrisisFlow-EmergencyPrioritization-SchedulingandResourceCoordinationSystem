const map = L.map("map", { zoomControl: true }).setView([30.3165, 78.0322], 11);

const streets = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap contributors"
}).addTo(map);
const light = L.tileLayer("https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap &copy; CARTO"
});
const dark = L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap &copy; CARTO"
});
L.control.layers({ Streets: streets, Light: light, Dark: dark }, null, { position: "topright" }).addTo(map);

const emergencyLayer = L.layerGroup().addTo(map);
const resourceLayer = L.layerGroup().addTo(map);
let searchMarker = null;
let firstLoad = true;

const TYPE_ICON = { Flood: "🌊", Earthquake: "🌍", Fire: "🔥", Landslide: "⛰️", "Major Accident": "💥" };
const RES_ICON = { Ambulance: "🚑", "Rescue Team": "🪢", "Medical Team": "⚕️", "Fire Unit": "🚒" };
const COLORS = { red: "#ef4444", green: "#16a34a", blue: "#2563eb", orange: "#f59e0b" };

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, c => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[c]);
}

function validCoordinates(item) {
    const la = Number(item.latitude), ln = Number(item.longitude);
    return item.latitude !== null && item.longitude !== null
        && Number.isFinite(la) && Number.isFinite(ln)
        && la >= -90 && la <= 90 && ln >= -180 && ln <= 180;
}

function pinIcon(emoji, color, pulse) {
    return L.divIcon({
        className: "",
        html: `<div class="pin ${pulse ? "pulse" : ""}" style="--pc:${COLORS[color]}"><div class="core">${emoji}</div></div>`,
        iconSize: [34, 34], iconAnchor: [17, 17], popupAnchor: [0, -16]
    });
}

function row(k, v) { return `<div class="row"><span>${k}</span><strong>${v}</strong></div>`; }

function addEmergencyMarker(e) {
    if (!validCoordinates(e)) return false;
    const resolved = ["Resolved", "Cancelled"].includes(e.responseStatus);
    const m = L.marker([+e.latitude, +e.longitude], {
        icon: pinIcon(TYPE_ICON[e.disasterType] || "⚠️", resolved ? "green" : "red", !resolved && e.priority === "CRITICAL"),
        title: e.disasterType
    });
    m.bindPopup(`<div class="popup"><h4>${TYPE_ICON[e.disasterType] || ""} Emergency #${e.emergencyId}</h4>
        ${row("Type", escapeHtml(e.disasterType))}${row("Location", escapeHtml(e.location))}
        ${row("People", escapeHtml(e.peopleAffected))}${row("Priority", escapeHtml(e.priority))}
        ${row("Allocation", escapeHtml(e.allocationStatus))}${row("Response", escapeHtml(e.responseStatus))}
        ${row("Resources", (e.assigned || []).length
            ? (e.assigned || []).map(a => escapeHtml(a.resourceName)).join(", ") : "none yet")}</div>`);
    m._resolved = resolved;
    m.addTo(emergencyLayer);
    return true;
}

function addResourceMarker(r) {
    if (!validCoordinates(r)) return false;
    const m = L.marker([+r.latitude, +r.longitude], {
        icon: pinIcon(RES_ICON[r.typeName] || "🚑", r.status === "Available" ? "blue" : "orange", false),
        title: r.resourceName
    });
    m.bindPopup(`<div class="popup"><h4>${RES_ICON[r.typeName] || ""} ${escapeHtml(r.resourceName)}</h4>
        ${row("Type", escapeHtml(r.typeName))}${row("Location", escapeHtml(r.location))}
        ${row("Capacity", escapeHtml(r.capacity))}${row("Status", escapeHtml(r.status))}</div>`);
    m.addTo(resourceLayer);
    return true;
}

/* Road-network routes from the backend; no straight-line segment is labelled as a road route. */
const routeCache = new Map();
async function addAssignmentLine(a) {
    const vals = [a.resourceLatitude, a.resourceLongitude, a.emergencyLatitude, a.emergencyLongitude];
    if (vals.some(v => v == null || !Number.isFinite(+v))) return false;
    const key = vals.map(v => Number(v).toFixed(4)).join(",");
    let route = routeCache.get(key);
    if (!route || Date.now() - route.cachedAt > 60000) {
        try {
            const url = `/api/routes?fromLat=${encodeURIComponent(vals[0])}&fromLon=${encodeURIComponent(vals[1])}&toLat=${encodeURIComponent(vals[2])}&toLon=${encodeURIComponent(vals[3])}`;
            const response = await fetch(url);
            if (!response.ok) throw new Error("Route service unavailable");
            route = await response.json(); route.cachedAt = Date.now(); routeCache.set(key, route);
        } catch (e) {
            L.polyline([[+vals[0], +vals[1]], [+vals[2], +vals[3]]], { color: "#f59e0b", weight: 2, opacity: .55, dashArray: "3 7" })
              .bindTooltip(`${escapeHtml(a.resourceName)} → emergency #${a.emergencyId}: road route unavailable (dashed line is approximate only)`).addTo(resourceLayer);
            return false;
        }
    }
    const coords = (route.geometry || []).map(c => [Number(c[1]), Number(c[0])]);
    if (!coords.length) return false;
    L.polyline(coords, { color: "#2563eb", weight: 4, opacity: .8 })
      .bindTooltip(`${escapeHtml(a.resourceName)} → emergency #${a.emergencyId} · ${route.distanceKm} km · ${route.durationMinutes} min · ${escapeHtml(route.trafficLabel || "traffic status unknown")}`)
      .addTo(resourceLayer);
    return true;
}

function showAllMarkers() {
    const markers = [...emergencyLayer.getLayers(), ...resourceLayer.getLayers()];
    if (!markers.length) return;
    const bounds = L.featureGroup(markers).getBounds();
    if (bounds.isValid()) map.flyToBounds(bounds.pad(0.2), { maxZoom: 14, duration: 0.8 });
}

async function refreshMap() {
    const msg = document.getElementById("mapMessage");
    msg.textContent = "Loading map data…";
    try {
        const [er, rr, ar] = await Promise.all([fetch("/api/emergencies"), fetch("/api/resources"), fetch("/api/allocations")]);
        if (!er.ok || !rr.ok) throw new Error("Could not load map data from the server.");
        const [emergencies, resources] = await Promise.all([er.json(), rr.json()]);
        const allocations = ar.ok ? ((await ar.json()).allocations || []) : [];

        emergencyLayer.clearLayers();
        resourceLayer.clearLayers();
        const pe = emergencies.filter(addEmergencyMarker).length;
        const pr = resources.filter(addResourceMarker).length;
        await Promise.all(allocations.filter(a => a.status === "Active").map(addAssignmentLine));
        applyFilters();

        msg.textContent = `Showing ${pe} of ${emergencies.length} emergencies and ${pr} of ${resources.length} resources.`;
        if (firstLoad) { firstLoad = false; showAllMarkers(); }
    } catch (error) {
        msg.textContent = error.message;
    }
}

/* layer toggles */
function applyFilters() {
    const em = document.getElementById("toggleEmergencies").checked;
    const rs = document.getElementById("toggleResources").checked;
    const rv = document.getElementById("toggleResolved").checked;
    em ? map.addLayer(emergencyLayer) : map.removeLayer(emergencyLayer);
    rs ? map.addLayer(resourceLayer) : map.removeLayer(resourceLayer);
    emergencyLayer.getLayers().forEach(m => {
        const el = m.getElement();
        if (el) el.style.display = (!rv && m._resolved) ? "none" : "";
    });
}
["toggleEmergencies", "toggleResources", "toggleResolved"].forEach(id =>
    document.getElementById(id).addEventListener("change", applyFilters));

/* search by place name */
const searchInput = document.getElementById("placeSearch");
const searchApi = attachPlaceSearch(
    searchInput, document.createElement("input"), document.createElement("input"), null,
    (lat, lng, name) => {
        if (searchMarker) map.removeLayer(searchMarker);
        searchMarker = L.marker([lat, lng]).addTo(map)
            .bindPopup(`<div class="popup"><h4>📍 Searched place</h4>${escapeHtml(name || "")}</div>`).openPopup();
        map.flyTo([lat, lng], 15, { duration: 1 });
    }
);
searchInput.addEventListener("keydown", async e => {
    if (e.key === "Enter") {
        e.preventDefault();
        try { await searchApi.resolve(); } catch { /* ignore */ }
    }
});

refreshMap();
setInterval(refreshMap, 15000);


async function refreshNotifications() {
    const list = document.getElementById("notificationsList");
    if (!list) return;
    try {
        const response = await fetch("/api/notifications");
        if (!response.ok) throw new Error("Could not load notifications");
        const data = await response.json();
        document.getElementById("unreadCount").textContent = `(${data.unreadCount} unread)`;
        list.innerHTML = (data.notifications || []).slice(0, 12).map(n => `<div class="notification-row" style="padding:10px;border-bottom:1px solid #e5e7eb;display:flex;gap:12px;align-items:center;justify-content:space-between"><div><strong>${escapeHtml(n.eventType)}</strong><div>${escapeHtml(n.message)}</div><small>${escapeHtml(n.createdAt)} ${n.isRead ? "· Read" : "· Unread"}</small></div>${n.isRead ? "" : `<button data-notification-id="${Number(n.notificationId)}">Mark read</button>`}</div>`).join("") || "No notifications yet.";
        list.querySelectorAll("button[data-notification-id]").forEach(button => button.addEventListener("click", async () => {
            try { const r = await fetch("/api/notifications", { method:"POST", headers:{"Content-Type":"application/json", ...((sessionStorage.getItem("crisisflowKey")) ? {"X-API-Key":sessionStorage.getItem("crisisflowKey")} : {})}, body:JSON.stringify({notificationId:Number(button.dataset.notificationId)}) }); if (!r.ok) throw new Error(); await refreshNotifications(); } catch { alert("Could not mark notification as read. Check API key configuration."); }
        }));
    } catch (e) { list.textContent = e.message; }
}
refreshNotifications();
setInterval(refreshNotifications, 30000);
