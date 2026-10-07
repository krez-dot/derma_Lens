/**
 * DermaLens - Contribute to Research upload endpoint.
 *
 * Deploy this as a Google Apps Script Web App under YOUR OWN Google account. It receives
 * anonymized scan images from the app (base64-encoded JSON POST) and saves them into a folder
 * in YOUR Google Drive -- no billing, no credit card, uses the 400GB you already have.
 *
 * ── SETUP ─────────────────────────────────────────────────────────────────────────────────
 * 1. Go to https://script.google.com -> New project. Paste this entire file over Code.gs.
 * 2. Set the shared secret (must match CONTRIBUTION_UPLOAD_SECRET in local.properties):
 *      Project Settings (gear icon, left sidebar) -> Script Properties -> Add script property
 *      Property: SHARED_SECRET
 *      Value:    <the same value as CONTRIBUTION_UPLOAD_SECRET in your local.properties>
 *    Generate one with any random hex string (e.g. `openssl rand -hex 32`). Keep it only in
 *    Script Properties and the gitignored local.properties -- never in this file, since this
 *    file is committed to a public repo. If it ever leaks, rotate it in both places.
 * 3. Deploy -> New deployment -> gear icon -> "Web app".
 *      Execute as:      Me
 *      Who has access:  Anyone
 *    Click Deploy, authorize the permissions it asks for (this is your own script acting on
 *    your own Drive), then copy the "Web app URL" it gives you -- it looks like
 *    https://script.google.com/macros/s/AKfycb.../exec
 * 4. Paste that URL into local.properties as APPS_SCRIPT_URL=<that url>.
 * 5. Every time you edit this script, you must create a NEW deployment version (Deploy ->
 *    Manage deployments -> edit (pencil) -> New version -> Deploy) for changes to take effect --
 *    saving the file alone does not update a live Web App deployment.
 *
 * Images land in "DermaLens Contributions/<condition>/" -- one subfolder per detected condition
 * (e.g. "Eczema", "Scabies"), auto-created on first upload of that condition. Pre-sorting by
 * condition here is deliberate: it matches the per-condition Roboflow project layout used by
 * training/merge_and_train_multiclass.ipynb, so contributed images can be folded straight into a
 * future retraining run without manual sorting first.
 * ─────────────────────────────────────────────────────────────────────────────────────────
 */

var ROOT_FOLDER_NAME = "DermaLens Contributions";
var UNCATEGORIZED_FOLDER_NAME = "Uncategorized";

// The shared secret ships inside the app, so anyone who unpacks the APK can call this endpoint.
// These checks limit what such a caller could do to your Drive: only these folder names, only
// real JPEGs, nothing huge, and file names chosen here rather than by the caller.
var ALLOWED_CONDITIONS = ["Acne Vulgaris", "Eczema", "Melasma", "Tinea", "Warts", "Scabies"];
var MAX_IMAGE_BYTES = 5 * 1024 * 1024; // app uploads are ~60 KB crops; 5 MB leaves lots of room
var MAX_BASE64_LENGTH = Math.ceil(MAX_IMAGE_BYTES / 3) * 4;

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);

    var expectedSecret = PropertiesService.getScriptProperties().getProperty("SHARED_SECRET");
    if (!expectedSecret || body.secret !== expectedSecret) {
      return jsonResponse({ status: "error", message: "unauthorized" });
    }
    if (typeof body.imageBase64 !== "string" || !body.imageBase64) {
      return jsonResponse({ status: "error", message: "missing imageBase64" });
    }
    // Checked before decoding, so an oversized request is rejected without the work of decoding it
    if (body.imageBase64.length > MAX_BASE64_LENGTH) {
      return jsonResponse({ status: "error", message: "image too large" });
    }

    var bytes = Utilities.base64Decode(body.imageBase64);
    // JPEG files start with FF D8 (bytes are signed here, hence -1 and -40)
    if (bytes.length < 3 || bytes[0] !== -1 || bytes[1] !== -40) {
      return jsonResponse({ status: "error", message: "not a JPEG image" });
    }

    var requested = (body.condition || "").toString().trim();
    var conditionName = ALLOWED_CONDITIONS.indexOf(requested) >= 0 ? requested : UNCATEGORIZED_FOLDER_NAME;
    var filename = conditionName.replace(/[^A-Za-z0-9]/g, "_") + "_" + Utilities.getUuid() + ".jpg";
    var blob = Utilities.newBlob(bytes, "image/jpeg", filename);
    getOrCreateConditionFolder(conditionName).createFile(blob);

    return jsonResponse({ status: "ok" });
  } catch (err) {
    // No internal details back to the caller
    return jsonResponse({ status: "error", message: "upload failed" });
  }
}

function getOrCreateRootFolder() {
  var existing = DriveApp.getFoldersByName(ROOT_FOLDER_NAME);
  if (existing.hasNext()) return existing.next();
  return DriveApp.createFolder(ROOT_FOLDER_NAME);
}

function getOrCreateConditionFolder(conditionName) {
  var root = getOrCreateRootFolder();
  var existing = root.getFoldersByName(conditionName);
  if (existing.hasNext()) return existing.next();
  return root.createFolder(conditionName);
}

function jsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
      .setMimeType(ContentService.MimeType.JSON);
}
