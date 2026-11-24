/* ================= helpers ================= */
const TYPE_ICON = { Flood: "🌊", Earthquake: "🌍", Fire: "🔥", Landslide: "⛰️", "Major Accident": "💥" };
const RES_ICON = { Ambulance: "🚑", "Rescue Team": "🪢", "Medical Team": "⚕️", "Fire Unit": "🚒" };
let allEmergencies = [];

function showMessage(text, isError = false) {
    const stack = document.getElementById("toasts");
    if (!stack) return;
    const t = document.createElement("div");
    t.className = "toast" + (isError ? " error" : "");
    t.textContent = (isError ? "⚠ " : "✓ ") + text;
    stack.appendChild(t);
    setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 300); }, 3500);
}

function getApiKey() {
    try { return sessionStorage.getItem("crisisflowKey") || ""; } catch { return ""; }
}
function setApiKey(v) {
    try { sessionStorage.setItem("crisisflowKey", v); } catch { /* ignore */ }
}

async function api(url, options = {}, retried = false) {
    const key = getApiKey();
    const response = await fetch(url, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...(key ? { "X-API-Key": key } : {}),
            ...(options.headers || {})
        }
    });
    let data;
    try { data = await response.json(); }
    catch { throw new Error("The server returned an invalid response."); }
    if (response.status === 401 && !retried) {
        const entered = window.prompt("This server needs an API key for changes. Enter it:");
        if (entered) { setApiKey(entered.trim()); return api(url, options, true); }
    }
    if (!response.ok) {
        const err = new Error(data.error || `Request failed (${response.status})`);
        err.status = response.status;
        err.data = data;
        throw err;
    }
    return data;
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, c => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[c]);
}

function badge(text) {
    const t = escapeHtml(text);
    return `<span class="badge b-${t}">${t}</span>`;
}

function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}

/* animated counter */
function countTo(id, target) {
    const el = document.getElementById(id);
    if (!el) return;
    const start = Number(el.textContent) || 0;
    if (start === target) { el.textContent = target; return; }
    const t0 = performance.now(), dur = 600;
    (function step(now) {
        const p = Math.min(1, (now - t0) / dur);
        el.textContent = Math.round(start + (target - start) * (1 - Math.pow(1 - p, 3)));
        if (p < 1) requestAnimationFrame(step);
    })(t0);
}

/* ================= navigation ================= */
const TITLES = {
    dashboard: ["Dashboard", "Monitor incidents and coordinate response resources."],
    emergencies: ["Emergencies", "Register incidents and track their response."],
    resources: ["Resources", "Manage ambulances, rescue, medical and fire units."],
    allocations: ["Allocations", "Match available resources to pending emergencies."],
    notifications: ["Notifications", "System alerts and event notifications."],
    reports: ["Reports", "Operational summary of current records."],
    settings: ["Settings / Help", "System information."]
};

function showSection(id) {
    document.querySelectorAll(".content-section").forEach(s => { s.hidden = s.id !== id; });
    document.querySelectorAll("[data-section]").forEach(l => l.classList.toggle("active", l.dataset.section === id));
    const [title, sub] = TITLES[id] || TITLES.dashboard;
    setText("pageTitle", title);
    setText("pageSubtitle", sub);
    pickers.forEach(p => setTimeout(() => p.map.invalidateSize(), 50));
    if (location.hash !== "#" + id) history.replaceState(null, "", "#" + id);
}

document.querySelectorAll("[data-section]").forEach(link => {
    link.addEventListener("click", e => { e.preventDefault(); showSection(link.dataset.section); });
});

function tickClock() {
    setText("clock", new Date().toLocaleString([], { weekday: "short", hour: "2-digit", minute: "2-digit", second: "2-digit" }));
}
tickClock(); setInterval(tickClock, 1000);

/* ================= dashboard data ================= */
const lastSig = {};
let prevAssigned = null;   // emergencyId -> number of assigned resources

function changed(key, data) {
    const sig = JSON.stringify(data);
    if (lastSig[key] === sig) return false;
    lastSig[key] = sig;
    return true;
}

/* tell the operator when the system assigned something on its own */
function announceAutoAssignments(emergencies) {
    const now = new Map(emergencies.map(e => [e.emergencyId, (e.assigned || []).length]));
    if (prevAssigned) {
        emergencies.forEach(e => {
            const before = prevAssigned.get(e.emergencyId) || 0;
            const after = (e.assigned || []).length;
            if (after > before) {
                const names = e.assigned.slice(before).map(a => a.resourceName).join(", ");
                showMessage(`Emergency #${e.emergencyId} auto-assigned: ${names}`);
            }
        });
    }
    prevAssigned = now;
}

async function refreshDashboard() {
    let hasError = false;

    try {
        const emergencies = await api("/api/emergencies");
        if (!Array.isArray(emergencies)) throw new Error("Emergency API did not return a list.");
        allEmergencies = emergencies;
        announceAutoAssignments(emergencies);
        if (changed("emergencies", emergencies)) {
            countTo("totalEmergencies", emergencies.length);
            countTo("pendingEmergencies", emergencies.filter(e =>
                ["Pending", "Partial"].includes(e.allocationStatus) &&
                !["Resolved", "Cancelled"].includes(e.responseStatus)).length);
            countTo("activeResponses", emergencies.filter(e => !["Resolved", "Cancelled"].includes(e.responseStatus)).length);
            renderEmergencies();
            renderBars(emergencies);
            renderReport(emergencies);
            renderPriorityAlerts(emergencies);
        }
    } catch (error) {
        hasError = true;
        showMessage("Could not load emergencies: " + error.message, true);
    }

    try {
        const resources = await api("/api/resources");
        if (!Array.isArray(resources)) throw new Error("Resource API did not return a list.");
        if (changed("resources", resources)) {
            countTo("availableResources", resources.filter(r => r.status === "Available").length);
            renderResources(resources);
        }
    } catch (error) {
        hasError = true;
        showMessage("Could not load resources: " + error.message, true);
    }

    try {
        const data = await api("/api/allocations");
        if (changed("allocations", data)) renderAllocations(data);
    } catch (error) {
        hasError = true;
        showMessage("Could not load allocations: " + error.message, true);
    }
    
    try {
        const opportunities = await api("/api/reassignment-opportunities");
        if (changed("reassignment", opportunities)) renderReassignmentOpportunities(opportunities);
    } catch (error) {
        console.error("Could not load reassignment opportunities:", error);
    }
    
    try {
        const notifData = await api("/api/notifications");
        if (changed("notifications", notifData)) renderNotifications(notifData);
    } catch (error) {
        console.error("Could not load notifications:", error);
    }
    
    try {
        const metrics = await api("/api/metrics");
        if (changed("metrics", metrics)) renderMetrics(metrics);
    } catch (error) {
        console.error("Could not load metrics:", error);
    }
    
    return !hasError;
}

function statusSelect(e) {
    return `<select aria-label="Update emergency ${escapeHtml(e.emergencyId)}"
        onchange="updateEmergencyStatus(${Number(e.emergencyId)}, this.value)">
        ${["Reported", "Responding", "Resolved", "Cancelled"].map(s =>
            `<option value="${s}" ${s === e.responseStatus ? "selected" : ""}>${s}</option>`).join("")}
    </select>`;
}

function assignedChips(e) {
    const list = e.assigned || [];
    if (!list.length) return '<span class="muted">none yet</span>';
    return '<div class="chips">' + list.map(a =>
        `<span class="res-chip" title="${escapeHtml(a.typeName)}${a.distanceKm != null ? " · " + a.distanceKm + " km" : ""}">${RES_ICON[a.typeName] || "🚑"} ${escapeHtml(a.resourceName)}</span>`
    ).join("") + "</div>";
}

function emergencyRow(e) {
    return `<tr>
        <td>#${escapeHtml(e.emergencyId)}</td>
        <td>${TYPE_ICON[e.disasterType] || "⚠️"} ${escapeHtml(e.disasterType)}</td>
        <td>${escapeHtml(e.location)}</td>
        <td>${escapeHtml(e.peopleAffected)}</td>
        <td>${badge(e.priority)}</td>
        <td>${badge(e.allocationStatus)}</td>
        <td>${badge(e.responseStatus)}</td>
        <td>${assignedChips(e)}</td>
        <td><div class="actions">${statusSelect(e)}
            <button type="button" class="danger-button" title="Delete emergency"
                aria-label="Delete emergency ${escapeHtml(e.emergencyId)}"
                onclick="deleteEmergency(${Number(e.emergencyId)})">🗑</button></div></td>
    </tr>`;
}

const EMPTY = `<tr class="empty-row"><td colspan="9">No emergencies to show.</td></tr>`;

function renderEmergencies() {
    const recent = document.getElementById("emergencyTable");
    const all = document.getElementById("emergencyTableAll");

    if (recent) {
        const rows = allEmergencies.slice(0, 6).map(emergencyRow).join("");
        recent.innerHTML = rows || EMPTY;
    }
    if (all) {
        const q = (document.getElementById("emSearch")?.value || "").toLowerCase();
        const pr = document.getElementById("emPriority")?.value || "";
        const st = document.getElementById("emStatus")?.value || "";
        const rows = allEmergencies.filter(e =>
            (!q || (e.location + " " + e.disasterType).toLowerCase().includes(q)) &&
            (!pr || e.priority === pr) && (!st || e.responseStatus === st)
        ).map(emergencyRow).join("");
        all.innerHTML = rows || EMPTY;
    }
}
["emSearch", "emPriority", "emStatus"].forEach(id =>
    document.getElementById(id)?.addEventListener("input", renderEmergencies));

function renderBars(list) {
    const draw = (id, entries, colors) => {
        const el = document.getElementById(id);
        if (!el) return;
        const max = Math.max(1, ...entries.map(e => e[1]));
        el.innerHTML = entries.map(([k, v]) => `
            <div class="bar-row"><span>${escapeHtml(k)}</span>
            <div class="bar-track"><div class="bar-fill" style="background:${colors[k] || "#3b82f6"}" data-w="${(v / max) * 100}"></div></div>
            <span class="bar-num">${v}</span></div>`).join("");
        requestAnimationFrame(() => el.querySelectorAll(".bar-fill").forEach(b => (b.style.width = b.dataset.w + "%")));
    };
    const count = (key, keys) => keys.map(k => [k, list.filter(e => e[key] === k).length]);
    draw("priorityBars", count("priority", ["CRITICAL", "HIGH", "MEDIUM", "LOW"]),
        { CRITICAL: "#ef4444", HIGH: "#f97316", MEDIUM: "#eab308", LOW: "#22c55e" });
    draw("typeBars", count("disasterType", Object.keys(TYPE_ICON)).map(([k, v]) => [(TYPE_ICON[k] + " " + k), v]),
        {});
}

function renderReport(list) {
    const el = document.getElementById("reportSummary");
    if (!el) return;
    const people = list.reduce((s, e) => s + Number(e.peopleAffected || 0), 0);
    const items = [
        ["👥", "People affected", people],
        ["🆘", "Critical incidents", list.filter(e => e.priority === "CRITICAL").length],
        ["✅", "Resolved", list.filter(e => e.responseStatus === "Resolved").length],
        ["🚒", "Allocated", list.filter(e => e.allocationStatus === "Allocated").length]
    ];
    el.innerHTML = items.map(([i, l, v]) =>
        `<article class="summary-card"><span class="icon">${i}</span><span class="label">${l}</span><strong>${v}</strong></article>`).join("");
}

function renderResources(resources) {
    const tbody = document.getElementById("resourceTable");
    const cards = document.getElementById("resourceCards");

    if (cards) {
        cards.innerHTML = resources.map(r => `
            <div class="res-card">
                <div class="res-icon">${RES_ICON[r.typeName] || "🚑"}</div>
                <h4>${escapeHtml(r.resourceName)}</h4>
                <small>${escapeHtml(r.typeName)} · capacity ${escapeHtml(r.capacity)}</small><br>
                <small>📍 ${escapeHtml(r.location)}</small>
                <div style="margin-top:10px">${badge(r.status)}
                    ${r.assignedTo ? `<small> → emergency #${escapeHtml(r.assignedTo)}</small>` : ""}</div>
                <button type="button" class="danger-button"
                    aria-label="Delete resource ${escapeHtml(r.resourceName)}"
                    onclick="deleteResource(${Number(r.resourceId)}, ${escapeHtml(JSON.stringify(r.resourceName))})">🗑 Delete</button>
            </div>`).join("") || '<p class="muted">No resources yet.</p>';
    }
    if (!tbody) return;
    tbody.innerHTML = resources.map(r => `
        <tr>
            <td>#${escapeHtml(r.resourceId)}</td>
            <td>${escapeHtml(r.resourceName)}</td>
            <td>${escapeHtml(r.typeName)}</td>
            <td>${escapeHtml(r.capacity)}</td>
            <td>${escapeHtml(r.location)}</td>
            <td>${badge(r.status)}</td>
            <td>${r.assignedTo ? "#" + escapeHtml(r.assignedTo) : '<span class="muted">—</span>'}</td>
            <td><div class="actions"><select aria-label="Update resource ${escapeHtml(r.resourceId)}"
                ${r.status === "Reserved" ? "disabled" : ""}
                onchange="updateResourceStatus(${Number(r.resourceId)}, this.value)">
                ${(r.status === "Reserved" ? ["Reserved"] : ["Available", "Unavailable"]).map(s =>
                    `<option value="${s}" ${s === r.status ? "selected" : ""}>${s}</option>`).join("")}
            </select>
            <button type="button" class="danger-button" title="Delete resource"
                aria-label="Delete resource ${escapeHtml(r.resourceId)}"
                onclick="deleteResource(${Number(r.resourceId)}, ${escapeHtml(JSON.stringify(r.resourceName))})">🗑</button></div></td>
        </tr>`).join("");
}

function renderAllocations(data) {
    const unmet = document.getElementById("unmetList");
    if (unmet) {
        unmet.innerHTML = (data.unmet || []).map(u => `
            <div class="unmet-item">
                <strong>#${escapeHtml(u.emergencyId)} ${TYPE_ICON[u.disasterType] || ""} ${escapeHtml(u.disasterType)}</strong>
                ${badge(u.priority)} ${badge(u.allocationStatus)}
                <div class="muted">${escapeHtml(u.location)}</div>
                <div>Still needs: ${u.needs.map(n => `${n.units} × ${RES_ICON[n.typeName] || ""} ${escapeHtml(n.typeName)}`).join(", ")}</div>
            </div>`).join("") || '<p class="muted">✓ Every open emergency has the resources it needs.</p>';
    }
    const tbody = document.getElementById("allocationTable");
    if (tbody) {
        tbody.innerHTML = (data.allocations || []).map(a => `
            <tr>
                <td>#${escapeHtml(a.emergencyId)} ${TYPE_ICON[a.disasterType] || ""} ${escapeHtml(a.disasterType)}<br><small class="muted">${escapeHtml(a.emergencyLocation)}</small></td>
                <td>${escapeHtml(a.resourceName)}</td>
                <td>${RES_ICON[a.typeName] || ""} ${escapeHtml(a.typeName)}</td>
                <td>${a.distanceKm != null ? escapeHtml(a.distanceKm) + " km" : "—"}</td>
                <td>${a.etaMinutes != null ? "~" + escapeHtml(a.etaMinutes) + " min" : "—"}</td>
                <td>${badge(a.status)}</td>
                <td>${escapeHtml(a.allocatedAt)}</td>
            </tr>`).join("") || '<tr class="empty-row"><td colspan="7">No assignments yet.</td></tr>';
    }
}

/* ================= deleting ================= */
async function deleteEmergency(id) {
    if (!window.confirm(`Delete emergency #${id}? Any resources assigned to it will be freed and given to other waiting emergencies.`)) return;
    try {
        await api(`/api/emergencies/${id}`, { method: "DELETE" });
        showMessage(`Emergency #${id} deleted.`);
    } catch (error) {
        showMessage("Could not delete emergency: " + error.message, true);
    }
    await refreshDashboard();
}

async function deleteResource(id, name, force = false) {
    if (!force && !window.confirm(`Delete resource "${name}"?`)) return;
    try {
        const result = await api(`/api/resources/${id}${force ? "?force=true" : ""}`, { method: "DELETE" });
        showMessage(result.message || "Resource deleted.");
    } catch (error) {
        if (error.status === 409 && error.data && error.data.canForce) {
            if (window.confirm(`${error.message}\n\nDelete anyway and reassign?`)) {
                return deleteResource(id, name, true);
            }
        } else {
            showMessage("Could not delete resource: " + error.message, true);
        }
    }
    await refreshDashboard();
}

/* resource-type dropdown comes from the database, not hard-coded ids */
async function loadResourceTypes() {
    const select = document.querySelector("#resourceForm select[name=typeId]");
    if (!select) return;
    try {
        const types = await api("/api/resource-types");
        select.innerHTML = '<option value="">Choose type</option>' + types.map(t =>
            `<option value="${Number(t.typeId)}">${RES_ICON[t.typeName] || ""} ${escapeHtml(t.typeName)}</option>`).join("");
    } catch { /* keep the built-in options */ }
}

/* ================= place-name search + map picker ================= */
const pickers = [];

document.querySelectorAll("form[data-geo]").forEach(form => {
    const locInput = form.elements.location;
    const lat = form.elements.latitude;
    const lng = form.elements.longitude;
    const status = form.querySelector("[data-status]");
    const mapDiv = form.querySelector("[data-picker]");

    let marker = null;
    const map = L.map(mapDiv).setView([30.3165, 78.0322], 11);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19, attribution: "&copy; OpenStreetMap contributors"
    }).addTo(map);

    function drop(la, ln, fly = true) {
        if (marker) marker.setLatLng([la, ln]); else marker = L.marker([la, ln]).addTo(map);
        if (fly) map.flyTo([la, ln], Math.max(map.getZoom(), 14), { duration: .8 });
    }

    const search = attachPlaceSearch(locInput, lat, lng, status, (la, ln) => drop(la, ln));

    map.on("click", async e => {
        const { lat: la, lng: ln } = e.latlng;
        drop(la, ln, false);
        search.setPoint(la, ln, null);
        try { locInput.value = await reversePlace(la, ln); } catch { locInput.value = locInput.value || `${la.toFixed(5)}, ${ln.toFixed(5)}`; }
    });

    form.addEventListener("reset", () => {
        if (marker) { map.removeLayer(marker); marker = null; }
        if (status) status.textContent = "";
    });

    form._geo = search;
    pickers.push({ map });
});

/* ================= forms ================= */
async function submitGeoForm(event, endpoint, build, okText, failText) {
    event.preventDefault();
    const form = event.target;
    const btn = form.querySelector("button[type=submit]");
    btn.disabled = true;
    try {
        const found = await form._geo.resolve();
        if (!found) throw new Error("Please enter a place name we can locate, or click the map.");
        const data = Object.fromEntries(new FormData(form).entries());
        const result = await api(endpoint, { method: "POST", body: JSON.stringify(build(data)) });
        showMessage(okText(result));
        form.reset();
        await refreshDashboard();
    } catch (error) {
        showMessage(failText + error.message, true);
    } finally {
        btn.disabled = false;
    }
}

document.getElementById("emergencyForm")?.addEventListener("submit", e =>
    submitGeoForm(e, "/api/emergencies", d => ({
        ...d, latitude: Number(d.latitude), longitude: Number(d.longitude), peopleAffected: Number(d.peopleAffected)
    }), r => `Emergency #${r.emergencyId} registered (${r.allocationStatus || "Pending"}).`, "Emergency registration failed: "));

document.getElementById("resourceForm")?.addEventListener("submit", e =>
    submitGeoForm(e, "/api/resources", d => ({
        ...d, typeId: Number(d.typeId), capacity: Number(d.capacity),
        latitude: Number(d.latitude), longitude: Number(d.longitude)
    }), () => "Resource added successfully.", "Could not add resource: "));

/* allocation (both buttons) */
document.querySelectorAll(".js-allocate").forEach(btn => btn.addEventListener("click", async () => {
    btn.disabled = true;
    try {
        const result = await api("/api/allocate-pending", { method: "POST", body: "{}" });
        showMessage(result.allocatedCount ? `Allocation completed. Resources assigned: ${result.allocatedCount}` : "Nothing to assign right now.");
        await refreshDashboard();
    } catch (error) {
        showMessage("Allocation failed: " + error.message, true);
    } finally {
        btn.disabled = false;
    }
}));

async function updateEmergencyStatus(emergencyId, status) {
    try {
        await api("/api/emergency-status", { method: "POST", body: JSON.stringify({ emergencyId, status }) });
        showMessage("Emergency status updated.");
    } catch (error) {
        showMessage("Could not update emergency: " + error.message, true);
    }
    await refreshDashboard();
}

async function updateResourceStatus(resourceId, status) {
    try {
        await api("/api/resource-status", { method: "POST", body: JSON.stringify({ resourceId, status }) });
        showMessage("Resource status updated.");
    } catch (error) {
        showMessage("Could not update resource: " + error.message, true);
    }
    await refreshDashboard();
}

document.getElementById("refreshButton")?.addEventListener("click", async e => {
    e.currentTarget.disabled = true;
    try { if (await refreshDashboard()) showMessage("Data refreshed."); }
    finally { e.currentTarget.disabled = false; }
});

/* start */
loadResourceTypes();
showSection(TITLES[location.hash.slice(1)] ? location.hash.slice(1) : "dashboard");
refreshDashboard();
setInterval(refreshDashboard, 15000);


/* ================= notifications ================= */
function renderNotifications(data) {
    const badge = document.getElementById("notifBadge");
    const list = document.getElementById("notificationList");
    
    if (badge) {
        if (data.unreadCount > 0) {
            badge.textContent = data.unreadCount;
            badge.hidden = false;
        } else {
            badge.hidden = true;
        }
    }
    
    if (list) {
        const notifications = data.notifications || [];
        list.innerHTML = notifications.length ? notifications.map(n => `
            <div class="notification-item ${n.isRead ? 'read' : 'unread'}">
                <div class="notif-header">
                    <span class="notif-event ${n.eventType}">${escapeHtml(n.eventType.replace(/_/g, ' '))}</span>
                    <span class="notif-time">${formatTime(n.createdAt)}</span>
                </div>
                <div class="notif-message">${escapeHtml(n.message)}</div>
                ${n.emergencyId ? `<a href="#emergencies" class="notif-link">View emergency #${n.emergencyId}</a>` : ''}
                ${!n.isRead ? `<button type="button" class="mark-read-btn" onclick="markNotificationRead(${n.notificationId})">Mark read</button>` : ''}
            </div>
        `).join('') : '<p class="muted">No notifications yet.</p>';
    }
}

async function markNotificationRead(id) {
    try {
        await api("/api/notifications", { method: "POST", body: JSON.stringify({ notificationId: id }) });
        await refreshDashboard();
    } catch (error) {
        showMessage("Could not mark notification as read: " + error.message, true);
    }
}

async function markAllNotificationsRead() {
    try {
        const data = await api("/api/notifications");
        for (const n of data.notifications || []) {
            if (!n.isRead) {
                await api("/api/notifications", { method: "POST", body: JSON.stringify({ notificationId: n.notificationId }) });
            }
        }
        await refreshDashboard();
        showMessage("All notifications marked as read.");
    } catch (error) {
        showMessage("Could not mark notifications as read: " + error.message, true);
    }
}

/* ================= metrics and waiting time ================= */
function renderMetrics(metrics) {
    const table = document.getElementById("metricsTable");
    if (!table) return;
    
    const active = metrics.filter(m => !["Resolved", "Cancelled"].includes(m.responseStatus));
    if (!active.length) {
        table.innerHTML = '<p class="muted">No active emergencies to display metrics for.</p>';
        return;
    }
    
    table.innerHTML = `<table>
        <thead><tr>
            <th>Emergency</th><th>Status</th><th>Waiting since reported</th>
            <th>Since allocation</th><th>Timestamps</th>
        </tr></thead>
        <tbody>${active.map(m => `
            <tr>
                <td>#${escapeHtml(m.emergencyId)}</td>
                <td>${badge(m.responseStatus)}</td>
                <td>${formatDuration(m.reportedSeconds)}</td>
                <td>${m.sinceAllocationSeconds != null ? formatDuration(m.sinceAllocationSeconds) : '<span class="muted">—</span>'}</td>
                <td class="timestamps">
                    ${m.reportedAt ? `<div>📍 Reported: ${formatTime(m.reportedAt)}</div>` : ''}
                    ${m.firstAllocatedAt ? `<div>✅ Allocated: ${formatTime(m.firstAllocatedAt)}</div>` : ''}
                    ${m.responseStartedAt ? `<div>🚀 Responding: ${formatTime(m.responseStartedAt)}</div>` : ''}
                    ${m.arrivedAt ? `<div>🏁 Arrived: ${formatTime(m.arrivedAt)}</div>` : ''}
                </td>
            </tr>
        `).join('')}</tbody>
    </table>`;
}

function renderPriorityAlerts(emergencies) {
    const alerts = document.getElementById("priorityAlerts");
    if (!alerts) return;
    
    const critical = emergencies.filter(e => 
        e.priorityScore && e.priorityScore >= 80 && 
        !["Resolved", "Cancelled"].includes(e.responseStatus)
    ).slice(0, 5);
    
    if (!critical.length) {
        alerts.innerHTML = '<p class="muted">✓ No critical priority alerts at this time.</p>';
        return;
    }
    
    alerts.innerHTML = critical.map(e => `
        <div class="alert-item priority-alert">
            <div class="alert-header">
                <strong>#${escapeHtml(e.emergencyId)} ${TYPE_ICON[e.disasterType] || '⚠️'} ${escapeHtml(e.disasterType)}</strong>
                ${badge('CRITICAL')}
                <span class="priority-score">Score: ${e.priorityScore}/100</span>
            </div>
            <div class="alert-location">📍 ${escapeHtml(e.location)}</div>
            ${e.priorityReason ? `<div class="alert-reason">${escapeHtml(e.priorityReason)}</div>` : ''}
            <div class="alert-actions">
                <button type="button" class="secondary-button" onclick="reassessPriority(${e.emergencyId})">Reassess priority</button>
                <a href="#emergencies" class="secondary-button">View details →</a>
            </div>
        </div>
    `).join('');
}

async function reassessPriority(emergencyId) {
    try {
        const result = await api("/api/priority", { method: "POST", body: JSON.stringify({ emergencyId }) });
        showMessage(`Priority reassessed: ${result.priority} (score ${result.score}/100)`);
        await refreshDashboard();
    } catch (error) {
        showMessage("Could not reassess priority: " + error.message, true);
    }
}

/* ================= time formatting ================= */
function formatTime(isoString) {
    if (!isoString) return '—';
    try {
        return new Date(isoString).toLocaleString([], { 
            month: 'short', day: 'numeric', 
            hour: '2-digit', minute: '2-digit' 
        });
    } catch { return isoString; }
}

function formatDuration(seconds) {
    if (seconds == null) return '—';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    if (m > 0) return `${m} min`;
    return `${seconds}s`;
}


/* ================= smart reassignment ================= */
function renderReassignmentOpportunities(data) {
    const container = document.getElementById("reassignmentOpportunities");
    if (!container) return;
    
    const opportunities = data.opportunities || [];
    
    if (!opportunities.length) {
        container.innerHTML = '<p class="muted">✓ No reassignment opportunities found. All resources are optimally assigned.</p>';
        return;
    }
    
    container.innerHTML = opportunities.map(opp => `
        <div class="reassignment-item">
            <div class="reassign-header">
                <div class="reassign-resource">
                    <strong>${RES_ICON[opp.resourceType] || '🚑'} ${escapeHtml(opp.resourceName)}</strong>
                    <span class="resource-type">${escapeHtml(opp.resourceType)}</span>
                </div>
                <span class="time-savings">⏱️ Saves ${Math.round(opp.timeSavingsMinutes)} min</span>
            </div>
            
            <div class="reassign-comparison">
                <div class="current-assignment">
                    <div class="label">Currently assigned to:</div>
                    <div class="emergency-info">
                        <strong>Emergency #${escapeHtml(opp.currentEmergencyId)}</strong>
                        ${badge(opp.currentPriority)}
                        <div class="distance">📍 ${opp.currentDistanceKm.toFixed(1)} km away</div>
                    </div>
                </div>
                
                <div class="arrow">→</div>
                
                <div class="suggested-assignment">
                    <div class="label">Could be reassigned to:</div>
                    <div class="emergency-info highlight">
                        <strong>Emergency #${escapeHtml(opp.suggestedEmergencyId)}</strong>
                        ${badge(opp.suggestedPriority)}
                        <div class="distance">📍 ${opp.suggestedDistanceKm.toFixed(1)} km away</div>
                    </div>
                </div>
            </div>
            
            <div class="reassign-reason">${escapeHtml(opp.reason)}</div>
            
            <div class="reassign-actions">
                <button type="button" class="primary-button" 
                    onclick="performReassignment(${opp.resourceId}, ${opp.currentEmergencyId}, ${opp.suggestedEmergencyId}, ${escapeHtml(JSON.stringify(opp.reason))})">
                    ✓ Reassign to closer emergency
                </button>
                <button type="button" class="secondary-button" 
                    onclick="dismissReassignment(this)">
                    Dismiss
                </button>
            </div>
        </div>
    `).join('');
}

async function performReassignment(resourceId, fromEmergencyId, toEmergencyId, reason) {
    const confirmed = window.confirm(
        `Reassign resource #${resourceId} from emergency #${fromEmergencyId} to emergency #${toEmergencyId}?\n\n` +
        `This will help respond faster to the closer emergency.`
    );
    
    if (!confirmed) return;
    
    try {
        const result = await api("/api/reassign", {
            method: "POST",
            body: JSON.stringify({
                resourceId,
                fromEmergencyId,
                toEmergencyId,
                reason
            })
        });
        
        showMessage(`Resource reassigned successfully! System will now allocate to emergency #${toEmergencyId}.`);
        await refreshDashboard();
    } catch (error) {
        showMessage("Could not reassign resource: " + error.message, true);
    }
}

function dismissReassignment(button) {
    const item = button.closest('.reassignment-item');
    if (item) {
        item.style.opacity = '0.5';
        item.style.pointerEvents = 'none';
        setTimeout(() => {
            item.remove();
            const container = document.getElementById("reassignmentOpportunities");
            if (container && !container.querySelector('.reassignment-item')) {
                container.innerHTML = '<p class="muted">✓ No more reassignment opportunities at this time.</p>';
            }
        }, 300);
    }
}
