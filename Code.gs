/**
 * Aplikasi Pendataan Lapangan
 * Google Apps Script Web App API
 *
 * API yang tersedia:
 *   GET  /exec?action=master&versi=7&token=...
 *   GET  /exec?action=cakupan&id_petugas=P001&token=...
 *   GET  /exec?page=dashboard&admin_token=...
 *   POST /exec  { action: "upload_foto", ... }
 *   POST /exec  { action: "sync", ... }
 *   POST /exec  { action: "login", ... }
 *
 * Konfigurasi wajib diletakkan pada Script Properties:
 *   SPREADSHEET_ID   = ID Google Spreadsheet
 *   DRIVE_FOLDER_ID  = ID folder Google Drive untuk foto
 *   REPORT_FOLDER_ID  = ID folder Drive untuk laporan PDF (opsional,
 *                       fallback ke DRIVE_FOLDER_ID)
 *   API_TOKEN        = token rahasia API
 *   DASHBOARD_TOKEN  = token khusus dashboard (disarankan)
 *   ADMIN_EMAILS     = email admin, dipisahkan koma (opsional)
 *
 * Konfigurasi opsional:
 *   SCRIPT_TIMEZONE  = Asia/Makassar
 *
 * Langkah awal:
 *   1. Buat Spreadsheet dan folder Drive.
 *   2. Isi Script Properties di Project Settings.
 *   3. Jalankan setupBackend() satu kali dari editor Apps Script.
 *   4. Deploy sebagai Web App:
 *        Execute as: Me
 *        Who has access: Anyone
 *
 * Catatan keamanan:
 *   Apps Script Web App tidak menyediakan header request pada object event
 *   secara konsisten. Karena itu token GET dikirim sebagai query parameter,
 *   sedangkan token POST dikirim di body JSON, sesuai kontrak API PRD.
 */

var CONFIG = {
  MAX_SYNC_RECORDS: 20,
  MAX_REQUEST_BYTES: 5 * 1024 * 1024,
  MAX_PHOTO_BYTES: 5 * 1024 * 1024,
  MAX_PHOTOS_PER_RECORD: 3,
  MAX_MASTER_IMPORT_ROWS: 10000,
  MAX_REPORT_RECORDS: 1000,
  MAX_REPORT_PHOTOS: 300,
  REPORT_PHOTO_MAX_WIDTH: 240,
  REPORT_PHOTO_MAX_HEIGHT: 170,
  LOCK_TIMEOUT_MS: 30 * 1000,
  MAX_TIME_DRIFT_MS: 10 * 60 * 1000,
  DEFAULT_TIMEZONE: 'Asia/Makassar',
  SHEETS: {
    PETUGAS: 'petugas',
    PENUGASAN: 'penugasan',
    MASTER_WILAYAH: 'master_wilayah',
    PENDATAAN: 'pendataan',
    REKAP_CAKUPAN: 'rekap_cakupan',
    LOG_SYNC: 'log_sync'
  }
};

var SHEET_HEADERS = {
  PETUGAS: [
    'id_petugas',
    'nama',
    'hash_pin',
    'wilayah_penugasan',
    'aktif'
  ],
  PENUGASAN: [
    'id_penugasan',
    'id_petugas',
    'kode_kab',
    'kode_kec',
    'kode_desa',
    'kode_sls',
    'aktif',
    'keterangan',
    'created_at',
    'updated_at'
  ],
  MASTER_WILAYAH: [
    'kode_kab',
    'kabupaten',
    'kode_kec',
    'nama_kec',
    'kode_desa',
    'nama_desa',
    'kode_sls',
    'nama_sls',
    'target_responden',
    'lat_centroid',
    'lon_centroid',
    'versi_master'
  ],
  PENDATAAN: [
    'id_record',
    'id_petugas',
    'kode_kec',
    'nama_kec',
    'kode_desa',
    'nama_desa',
    'kode_sls',
    'nama_sls',
    'jenis_objek',
    'nama_objek',
    'alamat',
    'status_pendataan',
    'catatan',
    'latitude',
    'longitude',
    'akurasi_m',
    'is_mock',
    'waktu_pendataan',
    'url_foto_1',
    'url_foto_2',
    'url_foto_3',
    'waktu_diterima_server',
    'flag_waktu',
    'versi_app'
  ],
  REKAP_CAKUPAN: [
    'kode_sls',
    'target',
    'jumlah_terdata',
    'persen_cakupan'
  ],
  LOG_SYNC: [
    'waktu',
    'id_petugas',
    'jumlah_record',
    'status',
    'keterangan'
  ]
};

/**
 * GET endpoint.
 *
 * Supported actions:
 *   master   : mengambil master wilayah berdasarkan versi.
 *   cakupan  : mengambil rekap cakupan sesuai wilayah petugas.
 */
function doGet(e) {
  try {
    var params = e && e.parameter ? e.parameter : {};

    /*
     * Route dashboard dipisahkan sebelum validasi API token agar token
     * mobile tidak perlu ditanam ke halaman admin. Action master/cakupan
     * tetap berjalan persis seperti kontrak Android sebelumnya.
     */
    if (toText_(params.page).toLowerCase() === 'dashboard') {
      return handleDashboardPage_(params);
    }

    assertToken_(params.token || params.api_token);

    var action = String(params.action || '').toLowerCase();

    if (action === 'master') {
      return handleGetMaster_(params);
    }

    if (action === 'cakupan') {
      return handleGetCoverage_(params);
    }

    throw new ApiError(
      'ACTION_TIDAK_DIDUKUNG',
      'Action GET tidak didukung. Gunakan master atau cakupan.'
    );
  } catch (error) {
    return errorResponseFromException_(error, 'doGet');
  }
}

/**
 * POST endpoint.
 *
 * Supported actions:
 *   upload_foto : menyimpan foto ber-watermark ke Google Drive.
 *   sync        : menyimpan metadata pendataan ke Google Spreadsheet.
 *   login       : memvalidasi id petugas dan PIN.
 */
function doPost(e) {
  try {
    var rawBody = getRawRequestBody_(e);
    var requestBytes = byteLength_(rawBody);

    if (requestBytes > CONFIG.MAX_REQUEST_BYTES) {
      throw new ApiError(
        'REQUEST_TERLALU_BESAR',
        'Ukuran request melebihi batas 5 MB.'
      );
    }

    var payload;
    try {
      payload = JSON.parse(rawBody);
    } catch (parseError) {
      throw new ApiError(
        'JSON_TIDAK_VALID',
        'Body request harus berupa JSON yang valid.'
      );
    }

    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      throw new ApiError(
        'PAYLOAD_TIDAK_VALID',
        'Payload request harus berupa object JSON.'
      );
    }

    assertToken_(payload.token || payload.api_token);

    var action = String(payload.action || '').toLowerCase();

    if (action === 'login') {
      return handleLogin_(payload);
    }

    if (action === 'upload_foto') {
      return handleUploadPhoto_(payload);
    }

    if (action === 'sync') {
      return handleSync_(payload);
    }

    throw new ApiError(
      'ACTION_TIDAK_DIDUKUNG',
      'Action POST tidak didukung. Gunakan upload_foto atau sync.'
    );
  } catch (error) {
    return errorResponseFromException_(error, 'doPost');
  }
}

/**
 * Handler POST action=login.
 *
 * Kolom hash_pin pada sheet petugas harus berisi SHA-256 hex lowercase.
 * Gunakan hashPin("PIN") dari editor Apps Script untuk membuat nilainya.
 * PIN mentah tidak pernah disimpan atau dikembalikan oleh API.
 */
function handleLogin_(payload) {
  var idPetugas = requireString_(
    payload.id_petugas,
    'id_petugas',
    100
  );
  var pin = requireString_(
    payload.pin !== undefined ? payload.pin : payload.password,
    'pin',
    100
  );
  var rows = readSheetObjects_(CONFIG.SHEETS.PETUGAS);
  var worker = null;

  rows.some(function(row) {
    if (toText_(row.id_petugas) !== idPetugas) {
      return false;
    }

    worker = row;
    return true;
  });

  if (!worker) {
    throw new ApiError(
      'KREDENSIAL_TIDAK_VALID',
      'Kode petugas atau PIN tidak valid.'
    );
  }

  var isActive = normalizeBoolean_(
    worker.aktif,
    'aktif',
    false
  );
  var storedHash = normalizePinHash_(worker.hash_pin);
  var suppliedHash = sha256Hex_(pin);

  if (
    !isActive ||
    !storedHash ||
    !constantTimeEquals_(suppliedHash, storedHash)
  ) {
    throw new ApiError(
      'KREDENSIAL_TIDAK_VALID',
      'Kode petugas atau PIN tidak valid.'
    );
  }

  return jsonResponse_({
    ok: true,
    id_petugas: idPetugas,
    nama: toText_(worker.nama),
    wilayah_penugasan: worker.wilayah_penugasan,
    waktu_server: new Date().toISOString()
  });
}

/** Jalankan dari editor Apps Script untuk membuat hash SHA-256 PIN. */
function hashPin(pin) {
  return sha256Hex_(requireString_(pin, 'pin', 100));
}

/**
 * Handler GET action=master.
 */
function handleGetMaster_(params) {
  var clientVersion = parseIntegerParameter_(
    params.versi,
    'versi',
    0
  );
  var masterRows = getMasterRecords_();
  var serverVersion = getMasterVersion_(masterRows);
  var hasUpdate = clientVersion < serverVersion;

  return jsonResponse_({
    ok: true,
    versi_master: serverVersion,
    ada_perubahan: hasUpdate,
    data: hasUpdate ? masterRows.map(masterToApiRecord_) : []
  });
}

/**
 * Handler GET action=cakupan.
 */
function handleGetCoverage_(params) {
  var idPetugas = requireString_(
    params.id_petugas,
    'id_petugas',
    100
  );

  var worker = getActiveWorkerById_(idPetugas);
  var data = computeCoverageData_(worker);

  return jsonResponse_({
    ok: true,
    id_petugas: idPetugas,
    waktu_server: new Date().toISOString(),
    data: data
  });
}

/**
 * Handler POST action=upload_foto.
 *
 * Upload dibuat idempoten berdasarkan gabungan id_record dan id_foto.
 * Jika request yang sama diulang, file yang sudah ada dikembalikan dan
 * tidak dibuat file Drive kedua.
 */
function handleUploadPhoto_(payload) {
  var idRecord = requireUuidV4_(
    payload.id_record,
    'id_record'
  );
  var idFoto = requireUuid_(
    payload.id_foto,
    'id_foto'
  );
  var originalName = requireString_(
    payload.nama_file,
    'nama_file',
    150
  );
  var mime = normalizeMimeType_(payload.mime);
  var base64 = requireString_(
    payload.data_base64,
    'data_base64',
    null
  );
  var bytes = decodeBase64_(base64);

  if (bytes.length > CONFIG.MAX_PHOTO_BYTES) {
    throw new ApiError(
      'FOTO_TERLALU_BESAR',
      'Ukuran foto melebihi batas 5 MB.'
    );
  }

  var safeName = sanitizeFileName_(originalName);
  var fileName = sanitizeFileName_(
    idRecord + '_' + idFoto + '_' + safeName
  );

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new ApiError(
      'SERVER_SIBUK',
      'Server sedang memproses request lain. Silakan coba lagi.'
    );
  }

  try {
    var folder = getDriveFolder_();
    var existing = findFileByName_(folder, fileName);

    if (existing) {
      return jsonResponse_({
        ok: true,
        drive_file_id: existing.getId(),
        url: existing.getUrl(),
        id_record: idRecord,
        id_foto: idFoto,
        duplikat: true
      });
    }

    var blob = Utilities.newBlob(bytes, mime, fileName);
    var file = folder.createFile(blob);
    file.setDescription(
      'Aplikasi Pendataan Lapangan | ' +
      'id_record=' + idRecord +
      ' | id_foto=' + idFoto
    );

    return jsonResponse_({
      ok: true,
      drive_file_id: file.getId(),
      url: file.getUrl(),
      id_record: idRecord,
      id_foto: idFoto
    });
  } finally {
    lock.releaseLock();
  }
}

/**
 * Handler POST action=sync.
 *
 * Proses pemeriksaan dan append dibungkus satu Script Lock agar dua request
 * bersamaan tidak sama-sama melihat id_record sebagai record baru.
 */
function handleSync_(payload) {
  var idPetugas = requireString_(
    payload.id_petugas,
    'id_petugas',
    100
  );
  var records = payload.records;

  if (!Array.isArray(records)) {
    throw new ApiError(
      'RECORDS_TIDAK_VALID',
      'Field records harus berupa array.'
    );
  }

  if (records.length === 0) {
    throw new ApiError(
      'RECORDS_KOSONG',
      'Minimal satu record harus dikirim.'
    );
  }

  if (records.length > CONFIG.MAX_SYNC_RECORDS) {
    throw new ApiError(
      'BATCH_TERLALU_BESAR',
      'Maksimal 20 record per request.'
    );
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new ApiError(
      'SERVER_SIBUK',
      'Server sedang memproses request lain. Silakan coba lagi.'
    );
  }

  try {
    var worker = getActiveWorkerById_(idPetugas);
    var masterIndex = getMasterIndex_();
    var sheet = getSheetOrThrow_(CONFIG.SHEETS.PENDATAAN);
    var headers = getSheetHeaders_(sheet);

    assertRequiredHeaders_(
      headers,
      SHEET_HEADERS.PENDATAAN,
      CONFIG.SHEETS.PENDATAAN
    );

    var existingIds = getExistingRecordIndex_(sheet, headers);
    var reservedIds = {};
    var results = [];
    var pendingRows = [];
    var nowMs = new Date().getTime();

    records.forEach(function(record) {
      var idRecord = record && record.id_record
        ? String(record.id_record).trim()
        : '';
      var result = {
        id_record: idRecord,
        status: 'GAGAL'
      };

      results.push(result);

      try {
        idRecord = requireUuidV4_(idRecord, 'id_record');
        result.id_record = idRecord;

        /*
         * Untuk retry, record yang telah tersimpan langsung dikonfirmasi
         * tanpa append ulang. Ini adalah inti idempotensi server.
         */
        if (existingIds[idRecord]) {
          result.status = 'TERKIRIM';
          result.duplikat = true;
          return;
        }

        if (reservedIds[idRecord]) {
          throw new ApiError(
            'DUPLIKAT_DALAM_BATCH',
            'id_record yang sama muncul lebih dari satu kali dalam batch.'
          );
        }

        var normalized = normalizeRecordForSync_(
          record,
          idPetugas,
          worker,
          masterIndex,
          nowMs
        );

        pendingRows.push({
          result: result,
          row: buildPendataanRow_(normalized, headers)
        });
        reservedIds[idRecord] = true;
      } catch (recordError) {
        result.status = 'GAGAL';
        result.pesan = publicErrorMessage_(recordError);
      }
    });

    if (pendingRows.length > 0) {
      var values = pendingRows.map(function(item) {
        return item.row;
      });
      var startRow = sheet.getLastRow() + 1;

      try {
        sheet
          .getRange(startRow, 1, values.length, headers.length)
          .setValues(values);

        pendingRows.forEach(function(item) {
          item.result.status = 'TERKIRIM';
        });
      } catch (writeError) {
        var writeMessage = publicErrorMessage_(writeError);
        pendingRows.forEach(function(item) {
          item.result.status = 'GAGAL';
          item.result.pesan =
            'Gagal menulis ke Spreadsheet: ' + writeMessage;
        });
      }
    }

    var successCount = results.filter(function(item) {
      return item.status === 'TERKIRIM';
    }).length;
    var failureCount = results.filter(function(item) {
      return item.status === 'GAGAL';
    }).length;
    var syncStatus = failureCount === 0
      ? 'SUKSES'
      : successCount > 0
        ? 'SEBAGIAN'
        : 'GAGAL';

    /*
     * Sheet rekap adalah sheet turunan. Kegagalannya tidak boleh mengubah
     * record yang sudah berhasil disimpan di sheet pendataan menjadi gagal.
     */
    if (pendingRows.some(function(item) {
      return item.result.status === 'TERKIRIM';
    })) {
      try {
        refreshCoverageSheetUnlocked_();
      } catch (coverageError) {
        Logger.log(
          'Peringatan refresh rekap_cakupan: ' +
          publicErrorMessage_(coverageError)
        );
      }
    }

    tryLogSyncUnlocked_(
      idPetugas,
      records.length,
      syncStatus,
      'Terkirim=' + successCount + ', Gagal=' + failureCount
    );

    return jsonResponse_({
      ok: true,
      waktu_server: new Date(nowMs).toISOString(),
      jumlah_record: records.length,
      jumlah_terkirim: successCount,
      jumlah_gagal: failureCount,
      hasil: results
    });
  } finally {
    lock.releaseLock();
  }
}

/**
 * Normalisasi dan validasi record sebelum ditulis ke Spreadsheet.
 * Nama wilayah diambil dari master server, bukan dipercaya dari client.
 */
function normalizeRecordForSync_(
  record,
  idPetugas,
  worker,
  masterIndex,
  nowMs
) {
  if (!record || typeof record !== 'object' || Array.isArray(record)) {
    throw new ApiError(
      'RECORD_TIDAK_VALID',
      'Setiap item records harus berupa object.'
    );
  }

  var recordWorker = toText_(record.id_petugas);
  if (recordWorker && recordWorker !== idPetugas) {
    throw new ApiError(
      'PETUGAS_TIDAK_SESUAI',
      'id_petugas pada record berbeda dengan id_petugas request.'
    );
  }

  var kodeSls = requireString_(record.kode_sls, 'kode_sls', 50);
  var master = masterIndex[kodeSls];

  if (!master) {
    throw new ApiError(
      'WILAYAH_TIDAK_DITEMUKAN',
      'kode_sls tidak ditemukan pada master_wilayah.'
    );
  }

  if (!isAssignmentAllowed_(kodeSls, worker)) {
    throw new ApiError(
      'WILAYAH_DI_LUAR_PENUGASAN',
      'Record berada di luar wilayah penugasan petugas.'
    );
  }

  var kodeKec = requireString_(record.kode_kec, 'kode_kec', 50);
  var kodeDesa = requireString_(record.kode_desa, 'kode_desa', 50);

  if (kodeKec !== master.kode_kec || kodeDesa !== master.kode_desa) {
    throw new ApiError(
      'HIERARKI_WILAYAH_TIDAK_SESUAI',
      'Kombinasi kecamatan, desa, dan SLS tidak sesuai master.'
    );
  }

  var jenisObjek = normalizeJenisObjek_(record.jenis_objek);
  var namaObjek = requireString_(record.nama_objek, 'nama_objek', 100);
  var alamat = optionalString_(record.alamat, 'alamat', 200);
  var statusPendataan = normalizeStatusPendataan_(
    record.status_pendataan
  );
  var catatan = optionalString_(record.catatan, 'catatan', 500);

  if (statusPendataan === 'TIDAK_LENGKAP' && !catatan) {
    throw new ApiError(
      'CATATAN_WAJIB',
      'Catatan wajib diisi untuk status Terisi Tidak Lengkap.'
    );
  }

  var latitude = requiredNumber_(
    record.latitude,
    'latitude',
    -90,
    90
  );
  var longitude = requiredNumber_(
    record.longitude,
    'longitude',
    -180,
    180
  );
  var akurasi = requiredNumber_(
    record.akurasi_m !== undefined
      ? record.akurasi_m
      : record.accuracy_m,
    'akurasi_m',
    0,
    100000
  );
  var isMock = normalizeBoolean_(
    record.is_mock !== undefined ? record.is_mock : record.isMock,
    'is_mock',
    false
  );
  var waktuPendataan = normalizeEpochMillis_(
    record.waktu_pendataan,
    'waktu_pendataan'
  );
  var flagWaktu = Math.abs(nowMs - waktuPendataan) >
    CONFIG.MAX_TIME_DRIFT_MS
    ? 'SELISIH_GT_10_MENIT'
    : '';
  var versiApp = optionalString_(
    record.versi_app,
    'versi_app',
    50
  );
  var fotoRefs = normalizePhotoReferences_(record);

  if (
    fotoRefs.length < 1 ||
    fotoRefs.length > CONFIG.MAX_PHOTOS_PER_RECORD
  ) {
    throw new ApiError(
      'JUMLAH_FOTO_TIDAK_VALID',
      'Setiap record harus memiliki 1 sampai 3 foto.'
    );
  }

  var verifiedPhotos = fotoRefs.map(function(photo) {
    var verified = verifyDriveFileInConfiguredFolder_(
      photo.drive_file_id
    );
    return {
      id_foto: photo.id_foto,
      drive_file_id: photo.drive_file_id,
      url: verified.url,
      urutan: photo.urutan
    };
  });

  return {
    id_record: requireUuidV4_(record.id_record, 'id_record'),
    id_petugas: idPetugas,
    kode_kec: master.kode_kec,
    nama_kec: master.nama_kec,
    kode_desa: master.kode_desa,
    nama_desa: master.nama_desa,
    kode_sls: master.kode_sls,
    nama_sls: master.nama_sls,
    jenis_objek: jenisObjek,
    nama_objek: namaObjek,
    alamat: alamat,
    status_pendataan: statusPendataan,
    catatan: catatan,
    latitude: latitude,
    longitude: longitude,
    akurasi_m: akurasi,
    is_mock: isMock,
    waktu_pendataan: new Date(waktuPendataan),
    waktu_diterima_server: new Date(nowMs),
    flag_waktu: flagWaktu,
    versi_app: versiApp,
    foto: verifiedPhotos
  };
}

/**
 * Membangun baris berdasarkan nama header, sehingga urutan kolom yang sama
 * dengan PRD tetap aman meskipun Spreadsheet memiliki kolom tambahan.
 */
function buildPendataanRow_(record, headers) {
  var photos = record.foto || [];
  var cells = {
    id_record: record.id_record,
    id_petugas: record.id_petugas,
    kode_kec: record.kode_kec,
    nama_kec: record.nama_kec,
    kode_desa: record.kode_desa,
    nama_desa: record.nama_desa,
    kode_sls: record.kode_sls,
    nama_sls: record.nama_sls,
    jenis_objek: record.jenis_objek,
    nama_objek: record.nama_objek,
    alamat: record.alamat,
    status_pendataan: record.status_pendataan,
    catatan: record.catatan,
    latitude: record.latitude,
    longitude: record.longitude,
    akurasi_m: record.akurasi_m,
    is_mock: record.is_mock,
    waktu_pendataan: record.waktu_pendataan,
    url_foto_1: photos[0] ? photos[0].url : '',
    url_foto_2: photos[1] ? photos[1].url : '',
    url_foto_3: photos[2] ? photos[2].url : '',
    waktu_diterima_server: record.waktu_diterima_server,
    flag_waktu: record.flag_waktu,
    versi_app: record.versi_app
  };

  return headers.map(function(header) {
    var key = String(header || '').trim();
    return Object.prototype.hasOwnProperty.call(cells, key)
      ? cells[key]
      : '';
  });
}

/**
 * Mengembalikan referensi foto dari format utama:
 *
 * foto: [
 *   {
 *     id_foto: "uuid",
 *     drive_file_id: "file-id",
 *     url: "https://drive.google.com/...",
 *     urutan: 1
 *   }
 * ]
 *
 * Beberapa nama field alternatif juga diterima agar integrasi Android lebih
 * toleran selama kontrak final belum dibekukan.
 */
function normalizePhotoReferences_(record) {
  var source = record.foto !== undefined
    ? record.foto
    : record.photos !== undefined
      ? record.photos
      : null;

  if (typeof source === 'string') {
    try {
      source = JSON.parse(source);
    } catch (error) {
      throw new ApiError(
        'FOTO_TIDAK_VALID',
        'Field foto harus berupa array.'
      );
    }
  }

  var refs = [];

  function addReference(raw, fallbackOrder) {
    if (!raw) {
      return;
    }

    if (typeof raw === 'string') {
      raw = { drive_file_id: raw };
    }

    if (typeof raw !== 'object' || Array.isArray(raw)) {
      throw new ApiError(
        'FOTO_TIDAK_VALID',
        'Referensi foto harus berupa object.'
      );
    }

    var driveFileId = toText_(
      raw.drive_file_id || raw.file_id || raw.id_file
    );
    if (!driveFileId) {
      throw new ApiError(
        'FOTO_TIDAK_VALID',
        'drive_file_id foto wajib diisi.'
      );
    }

    var order = raw.urutan !== undefined
      ? parseInt(raw.urutan, 10)
      : fallbackOrder;

    if (!order || order < 1 || order > CONFIG.MAX_PHOTOS_PER_RECORD) {
      order = fallbackOrder;
    }

    refs.push({
      id_foto: toText_(raw.id_foto),
      drive_file_id: driveFileId,
      url: toText_(raw.url),
      urutan: order
    });
  }

  if (source !== null && source !== undefined) {
    if (!Array.isArray(source)) {
      throw new ApiError(
        'FOTO_TIDAK_VALID',
        'Field foto harus berupa array.'
      );
    }
    source.forEach(function(item, index) {
      addReference(item, index + 1);
    });
  } else {
    for (var i = 1; i <= CONFIG.MAX_PHOTOS_PER_RECORD; i++) {
      var fallbackDriveFileId =
        record['drive_file_id_' + i] ||
        record['foto_drive_file_id_' + i] ||
        record['file_id_foto_' + i];

      if (fallbackDriveFileId) {
        addReference(
          {
            id_foto: record['id_foto_' + i],
            drive_file_id: fallbackDriveFileId,
            url: record['url_foto_' + i],
            urutan: i
          },
          i
        );
      }
    }
  }

  if (refs.length > CONFIG.MAX_PHOTOS_PER_RECORD) {
    throw new ApiError(
      'JUMLAH_FOTO_TIDAK_VALID',
      'Maksimal tiga foto dapat dikirim untuk satu record.'
    );
  }

  refs.sort(function(a, b) {
    return a.urutan - b.urutan;
  });

  var seenFiles = {};
  refs.forEach(function(ref) {
    if (seenFiles[ref.drive_file_id]) {
      throw new ApiError(
        'FOTO_DUPLIKAT',
        'drive_file_id foto tidak boleh duplikat.'
      );
    }
    seenFiles[ref.drive_file_id] = true;
  });

  return refs;
}

/**
 * Validasi file Drive agar metadata tidak dapat menunjuk ke file di luar
 * folder foto aplikasi.
 */
function verifyDriveFileInConfiguredFolder_(driveFileId) {
  var settings = getSettings_();
  if (!settings.driveFolderId) {
    throw new ApiError(
      'KONFIGURASI_BELUM_LENGKAP',
      'Script Property DRIVE_FOLDER_ID belum diatur.'
    );
  }

  var file;
  try {
    file = DriveApp.getFileById(driveFileId);
  } catch (error) {
    throw new ApiError(
      'FOTO_TIDAK_DITEMUKAN',
      'File foto tidak ditemukan atau tidak dapat diakses.'
    );
  }

  var parents = file.getParents();
  var belongsToConfiguredFolder = false;
  while (parents.hasNext()) {
    if (parents.next().getId() === settings.driveFolderId) {
      belongsToConfiguredFolder = true;
      break;
    }
  }

  if (!belongsToConfiguredFolder) {
    throw new ApiError(
      'FOTO_DI_FOLDER_SALAH',
      'File foto tidak berada pada folder Drive aplikasi.'
    );
  }

  return {
    drive_file_id: file.getId(),
    url: file.getUrl()
  };
}

/**
 * Menghitung cakupan berdasarkan data server.
 */
function computeCoverageData_(worker) {
  var masterRows = getMasterRecords_();
  var pendataanRows = readSheetObjects_(
    CONFIG.SHEETS.PENDATAAN
  );
  var counts = {};
  var seenRecords = {};

  pendataanRows.forEach(function(row) {
    var idRecord = toText_(row.id_record);
    var kodeSls = toText_(row.kode_sls);

    if (!idRecord || !kodeSls) {
      return;
    }

    if (!isAssignmentAllowed_(kodeSls, worker)) {
      return;
    }

    if (seenRecords[idRecord]) {
      return;
    }

    seenRecords[idRecord] = true;
    counts[kodeSls] = (counts[kodeSls] || 0) + 1;
  });

  return masterRows
    .filter(function(master) {
      return isAssignmentAllowed_(master.kode_sls, worker);
    })
    .map(function(master) {
      return {
        kode_sls: master.kode_sls,
        target: master.target_responden,
        terdata: counts[master.kode_sls] || 0
      };
    });
}

/**
 * Memperbarui sheet turunan rekap_cakupan.
 * Fungsi publik ini dapat dijalankan manual dari Apps Script editor.
 */
function refreshCoverage() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new Error('Server sedang sibuk. Coba lagi.');
  }

  try {
    return refreshCoverageSheetUnlocked_();
  } finally {
    lock.releaseLock();
  }
}

function refreshCoverageSheetUnlocked_() {
  var sheet = getSheetOrThrow_(CONFIG.SHEETS.REKAP_CAKUPAN);
  var headers = getSheetHeaders_(sheet);
  assertRequiredHeaders_(
    headers,
    SHEET_HEADERS.REKAP_CAKUPAN,
    CONFIG.SHEETS.REKAP_CAKUPAN
  );

  var masterRows = getMasterRecords_();
  var pendataanRows = readSheetObjects_(
    CONFIG.SHEETS.PENDATAAN
  );
  var counts = {};
  var seenRecords = {};

  pendataanRows.forEach(function(row) {
    var idRecord = toText_(row.id_record);
    var kodeSls = toText_(row.kode_sls);

    if (!idRecord || !kodeSls || seenRecords[idRecord]) {
      return;
    }

    seenRecords[idRecord] = true;
    counts[kodeSls] = (counts[kodeSls] || 0) + 1;
  });

  var values = masterRows.map(function(master) {
    var target = master.target_responden;
    var terdata = counts[master.kode_sls] || 0;
    var persen = target > 0 ? terdata / target : 0;
    var row = {
      kode_sls: master.kode_sls,
      target: target,
      jumlah_terdata: terdata,
      persen_cakupan: persen
    };

    return headers.map(function(header) {
      var key = String(header || '').trim();
      return Object.prototype.hasOwnProperty.call(row, key)
        ? row[key]
        : '';
    });
  });

  if (sheet.getLastRow() > 1) {
    sheet
      .getRange(2, 1, sheet.getLastRow() - 1, sheet.getLastColumn())
      .clearContent();
  }

  if (values.length > 0) {
    sheet
      .getRange(2, 1, values.length, headers.length)
      .setValues(values);
  }

  return {
    ok: true,
    jumlah_sls: values.length
  };
}

/**
 * Membuat sheet dan header sesuai PRD.
 * Jalankan satu kali setelah Script Properties diisi.
 */
function setupBackend() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new Error('Server sedang sibuk. Coba lagi.');
  }

  try {
    var spreadsheet = getSpreadsheet_();
    var definitions = [
      {
        name: CONFIG.SHEETS.PETUGAS,
        headers: SHEET_HEADERS.PETUGAS
      },
      {
        name: CONFIG.SHEETS.PENUGASAN,
        headers: SHEET_HEADERS.PENUGASAN
      },
      {
        name: CONFIG.SHEETS.MASTER_WILAYAH,
        headers: SHEET_HEADERS.MASTER_WILAYAH
      },
      {
        name: CONFIG.SHEETS.PENDATAAN,
        headers: SHEET_HEADERS.PENDATAAN
      },
      {
        name: CONFIG.SHEETS.REKAP_CAKUPAN,
        headers: SHEET_HEADERS.REKAP_CAKUPAN
      },
      {
        name: CONFIG.SHEETS.LOG_SYNC,
        headers: SHEET_HEADERS.LOG_SYNC
      }
    ];

    definitions.forEach(function(definition) {
      ensureSheetWithHeaders_(
        spreadsheet,
        definition.name,
        definition.headers
      );
    });

    return {
      ok: true,
      spreadsheet_id: spreadsheet.getId(),
      sheets: definitions.map(function(definition) {
        return definition.name;
      })
    };
  } finally {
    lock.releaseLock();
  }
}

/**
 * Membaca konfigurasi dari Script Properties.
 */
function getSettings_() {
  var properties = PropertiesService.getScriptProperties();
  return {
    spreadsheetId: String(
      properties.getProperty('SPREADSHEET_ID') || ''
    ).trim(),
    driveFolderId: String(
      properties.getProperty('DRIVE_FOLDER_ID') || ''
    ).trim(),
    reportFolderId: String(
      properties.getProperty('REPORT_FOLDER_ID') || ''
    ).trim(),
    apiToken: String(
      properties.getProperty('API_TOKEN') || ''
    ).trim(),
    dashboardToken: String(
      properties.getProperty('DASHBOARD_TOKEN') || ''
    ).trim(),
    adminEmails: String(
      properties.getProperty('ADMIN_EMAILS') || ''
    ).trim(),
    timeZone: String(
      properties.getProperty('SCRIPT_TIMEZONE') ||
      CONFIG.DEFAULT_TIMEZONE
    ).trim()
  };
}

function getSpreadsheet_() {
  var settings = getSettings_();

  if (settings.spreadsheetId) {
    try {
      return SpreadsheetApp.openById(settings.spreadsheetId);
    } catch (error) {
      throw new ApiError(
        'SPREADSHEET_TIDAK_DAPAT_DIAKSES',
        'Spreadsheet tidak dapat dibuka. Periksa SPREADSHEET_ID.'
      );
    }
  }

  var activeSpreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  if (activeSpreadsheet) {
    return activeSpreadsheet;
  }

  throw new ApiError(
    'KONFIGURASI_BELUM_LENGKAP',
    'Script Property SPREADSHEET_ID belum diatur.'
  );
}

function getDriveFolder_() {
  var settings = getSettings_();

  if (!settings.driveFolderId) {
    throw new ApiError(
      'KONFIGURASI_BELUM_LENGKAP',
      'Script Property DRIVE_FOLDER_ID belum diatur.'
    );
  }

  try {
    return DriveApp.getFolderById(settings.driveFolderId);
  } catch (error) {
    throw new ApiError(
      'FOLDER_DRIVE_TIDAK_DAPAT_DIAKSES',
      'Folder Drive tidak dapat dibuka. Periksa DRIVE_FOLDER_ID.'
    );
  }
}

function getSheetOrThrow_(sheetName) {
  var spreadsheet = getSpreadsheet_();
  var sheet = spreadsheet.getSheetByName(sheetName);

  if (!sheet) {
    throw new ApiError(
      'SHEET_TIDAK_DITEMUKAN',
      'Sheet ' + sheetName + ' belum tersedia. Jalankan setupBackend().'
    );
  }

  return sheet;
}

function ensureSheetWithHeaders_(spreadsheet, sheetName, headers) {
  var sheet = spreadsheet.getSheetByName(sheetName);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(sheetName);
  }

  var currentHeaders = getSheetHeaders_(sheet);
  var hasHeader = currentHeaders.some(function(header) {
    return String(header || '').trim() !== '';
  });

  if (!hasHeader) {
    sheet
      .getRange(1, 1, 1, headers.length)
      .setValues([headers]);
    sheet
      .getRange(1, 1, 1, headers.length)
      .setFontWeight('bold');
  } else {
    assertRequiredHeaders_(
      currentHeaders,
      headers,
      sheetName
    );
  }

  sheet.setFrozenRows(1);

  if (sheetName === CONFIG.SHEETS.MASTER_WILAYAH) {
    [1, 3, 5].forEach(function(column) {
      sheet.getRange(2, column, Math.max(sheet.getMaxRows() - 1, 1))
        .setNumberFormat('@');
    });
  }
}

function getSheetHeaders_(sheet) {
  var lastColumn = sheet.getLastColumn();
  if (lastColumn < 1) {
    return [];
  }

  return sheet
    .getRange(1, 1, 1, lastColumn)
    .getValues()[0]
    .map(function(header) {
      return String(header || '').trim();
    });
}

function assertRequiredHeaders_(actualHeaders, requiredHeaders, sheetName) {
  var missing = requiredHeaders.filter(function(header) {
    return actualHeaders.indexOf(header) === -1;
  });

  if (missing.length > 0) {
    throw new ApiError(
      'HEADER_TIDAK_SESUAI',
      'Sheet ' + sheetName +
      ' tidak memiliki kolom: ' + missing.join(', ') + '.'
    );
  }
}

function readSheetObjects_(sheetName) {
  return readSheetObjectsFromSheet_(
    getSheetOrThrow_(sheetName)
  );
}

function readSheetObjectsFromSheet_(sheet) {
  return readSheetObjectRowsWithNumbers_(sheet).map(function(item) {
    return item.object;
  });
}

function readSheetObjectRowsWithNumbers_(sheet) {
  var lastRow = sheet.getLastRow();
  var lastColumn = sheet.getLastColumn();

  if (lastRow < 2 || lastColumn < 1) {
    return [];
  }

  var headers = getSheetHeaders_(sheet);
  var values = sheet
    .getRange(2, 1, lastRow - 1, lastColumn)
    .getValues();

  return values
    .map(function(row, index) {
      var object = {};
      var hasValue = false;

      headers.forEach(function(header, index) {
        if (!header) {
          return;
        }
        object[header] = row[index];
        if (row[index] !== '' && row[index] !== null) {
          hasValue = true;
        }
      });

      return hasValue
        ? {
          object: object,
          rowNumber: index + 2
        }
        : null;
    })
    .filter(function(item) {
      return item !== null;
    });
}

function getMasterRecords_() {
  var sheet = getSheetOrThrow_(CONFIG.SHEETS.MASTER_WILAYAH);
  var headers = getSheetHeaders_(sheet);
  assertRequiredHeaders_(
    headers,
    SHEET_HEADERS.MASTER_WILAYAH,
    CONFIG.SHEETS.MASTER_WILAYAH
  );

  var records = readSheetObjectsFromSheet_(sheet);
  var result = [];
  var seen = {};

  records.forEach(function(row) {
    var normalized = {
      kode_kab: toText_(row.kode_kab),
      kabupaten: toText_(row.kabupaten),
      kode_kec: toText_(row.kode_kec),
      nama_kec: toText_(row.nama_kec),
      kode_desa: toText_(row.kode_desa),
      nama_desa: toText_(row.nama_desa),
      kode_sls: toText_(row.kode_sls),
      nama_sls: toText_(row.nama_sls),
      target_responden: toNonNegativeInteger_(
        row.target_responden,
        0
      ),
      lat_centroid: optionalNumber_(row.lat_centroid),
      lon_centroid: optionalNumber_(row.lon_centroid),
      versi_master: toNonNegativeInteger_(
        row.versi_master,
        0
      )
    };

    if (!normalized.kode_sls) {
      return;
    }

    if (seen[normalized.kode_sls]) {
      throw new ApiError(
        'MASTER_DUPLIKAT',
        'kode_sls duplikat pada master_wilayah: ' +
        normalized.kode_sls
      );
    }

    seen[normalized.kode_sls] = true;
    result.push(normalized);
  });

  return result;
}

function getMasterIndex_() {
  var records = getMasterRecords_();
  var index = {};

  records.forEach(function(record) {
    index[record.kode_sls] = record;
  });

  return index;
}

function getMasterVersion_(masterRows) {
  return masterRows.reduce(function(maximum, row) {
    return Math.max(maximum, row.versi_master || 0);
  }, 0);
}

function masterToApiRecord_(master) {
  return {
    kode_kab: master.kode_kab,
    kabupaten: master.kabupaten,
    kode_kec: master.kode_kec,
    nama_kec: master.nama_kec,
    kode_desa: master.kode_desa,
    nama_desa: master.nama_desa,
    kode_sls: master.kode_sls,
    nama_sls: master.nama_sls,
    target_responden: master.target_responden,
    lat_centroid: master.lat_centroid,
    lon_centroid: master.lon_centroid,
    versi_master: master.versi_master
  };
}

function getActiveWorkerById_(idPetugas) {
  var rows = readSheetObjects_(CONFIG.SHEETS.PETUGAS);
  var found = null;

  rows.forEach(function(row) {
    if (found) {
      return;
    }

    if (toText_(row.id_petugas) === idPetugas) {
      found = {
        id_petugas: idPetugas,
        nama: toText_(row.nama),
        wilayah_penugasan: row.wilayah_penugasan,
        aktif: normalizeBoolean_(
          row.aktif,
          'aktif',
          false
        )
      };
    }
  });

  if (!found) {
    throw new ApiError(
      'PETUGAS_TIDAK_DITEMUKAN',
      'id_petugas tidak terdaftar.'
    );
  }

  if (!found.aktif) {
    throw new ApiError(
      'PETUGAS_TIDAK_AKTIF',
      'Petugas tidak aktif.'
    );
  }

  return found;
}

function isAssignmentAllowed_(kodeSls, worker) {
  var assignments = getWorkerAssignmentCodes_(worker);

  /*
   * Wilayah penugasan kosong berarti seluruh wilayah. Untuk pembatasan
   * wilayah, isi dengan kode kecamatan/desa/SLS yang dipisahkan koma,
   * titik koma, atau array JSON.
   */
  if (assignments.length === 0) {
    return true;
  }

  return assignments.some(function(assignment) {
    var code = String(assignment).trim();
    if (!code || code === '*' || code.toUpperCase() === 'SEMUA') {
      return true;
    }

    return (
      kodeSls.indexOf(code) === 0 ||
      code.indexOf(kodeSls) === 0
    );
  });
}

/*
 * Sheet penugasan bersifat tambahan dan kompatibel mundur. Jika sheet belum
 * dibuat atau belum berisi baris untuk petugas, sistem tetap menggunakan
 * kolom petugas.wilayah_penugasan yang sudah dipakai oleh aplikasi Android.
 */
function getWorkerAssignmentCodes_(worker) {
  if (!worker) {
    return [];
  }

  if (
    worker._assignmentCodesLoaded &&
    Array.isArray(worker._assignmentCodes)
  ) {
    return worker._assignmentCodes;
  }

  var assignments = [];
  var spreadsheet = getSpreadsheet_();
  var sheet = spreadsheet.getSheetByName(CONFIG.SHEETS.PENUGASAN);

  if (sheet) {
    var headers = getSheetHeaders_(sheet);
    var hasIdentityHeaders =
      headers.indexOf('id_petugas') >= 0 &&
      headers.indexOf('aktif') >= 0;

    if (hasIdentityHeaders) {
      readSheetObjectsFromSheet_(sheet).forEach(function(row) {
        if (
          toText_(row.id_petugas).toLowerCase() !==
          toText_(worker.id_petugas).toLowerCase()
        ) {
          return;
        }

        var active = normalizeBoolean_(
          row.aktif,
          'aktif',
          true
        );
        if (!active) {
          return;
        }

        [
          'kode_kab',
          'kode_kec',
          'kode_desa',
          'kode_sls'
        ].forEach(function(fieldName) {
          var code = toText_(row[fieldName]);
          if (code && assignments.indexOf(code) < 0) {
            assignments.push(code);
          }
        });
      });
    }
  }

  if (assignments.length === 0) {
    assignments = normalizeAssignments_(
      worker.wilayah_penugasan
    );
  }

  worker._assignmentCodes = assignments;
  worker._assignmentCodesLoaded = true;
  return assignments;
}

function normalizeAssignments_(value) {
  if (value === null || value === undefined || value === '') {
    return [];
  }

  if (Array.isArray(value)) {
    return value.map(function(item) {
      return String(item).trim();
    }).filter(function(item) {
      return item !== '';
    });
  }

  var text = String(value).trim();
  if (!text) {
    return [];
  }

  try {
    var parsed = JSON.parse(text);
    if (Array.isArray(parsed)) {
      return normalizeAssignments_(parsed);
    }
  } catch (error) {
    // Lanjutkan sebagai string daftar kode.
  }

  return text.split(/[,;|\s]+/).map(function(item) {
    return item.trim();
  }).filter(function(item) {
    return item !== '';
  });
}

function getExistingRecordIndex_(sheet, headers) {
  var columnIndex = headers.indexOf('id_record');
  if (columnIndex === -1) {
    throw new ApiError(
      'HEADER_TIDAK_SESUAI',
      'Kolom id_record tidak tersedia pada sheet pendataan.'
    );
  }

  var result = {};
  var lastRow = sheet.getLastRow();

  if (lastRow < 2) {
    return result;
  }

  var values = sheet
    .getRange(2, columnIndex + 1, lastRow - 1, 1)
    .getValues();

  values.forEach(function(row) {
    var idRecord = toText_(row[0]).toLowerCase();
    if (idRecord) {
      result[idRecord] = true;
    }
  });

  return result;
}

function tryLogSyncUnlocked_(
  idPetugas,
  jumlahRecord,
  status,
  keterangan
) {
  try {
    var sheet = getSheetOrThrow_(CONFIG.SHEETS.LOG_SYNC);
    var headers = getSheetHeaders_(sheet);
    assertRequiredHeaders_(
      headers,
      SHEET_HEADERS.LOG_SYNC,
      CONFIG.SHEETS.LOG_SYNC
    );

    var row = {
      waktu: new Date(),
      id_petugas: idPetugas,
      jumlah_record: jumlahRecord,
      status: status,
      keterangan: keterangan
    };
    var values = headers.map(function(header) {
      return Object.prototype.hasOwnProperty.call(row, header)
        ? row[header]
        : '';
    });

    sheet
      .getRange(sheet.getLastRow() + 1, 1, 1, headers.length)
      .setValues([values]);
  } catch (error) {
    Logger.log(
      'Gagal menulis log_sync: ' + publicErrorMessage_(error)
    );
  }
}

function findFileByName_(folder, fileName) {
  var files = folder.getFilesByName(fileName);
  return files.hasNext() ? files.next() : null;
}

function getRawRequestBody_(e) {
  if (e && e.postData && e.postData.contents) {
    return String(e.postData.contents);
  }

  /*
   * Fallback untuk pengujian manual dengan form parameter payload.
   */
  if (e && e.parameter && e.parameter.payload) {
    return String(e.parameter.payload);
  }

  throw new ApiError(
    'BODY_KOSONG',
    'Body POST tidak boleh kosong.'
  );
}

function byteLength_(text) {
  try {
    return Utilities.newBlob(String(text)).getBytes().length;
  } catch (error) {
    return String(text).length;
  }
}

function decodeBase64_(value) {
  var data = String(value).trim();
  var commaIndex = data.indexOf(',');

  if (data.indexOf('data:') === 0 && commaIndex >= 0) {
    data = data.substring(commaIndex + 1);
  }

  data = data.replace(/\s/g, '');

  try {
    return Utilities.base64Decode(data);
  } catch (error) {
    throw new ApiError(
      'BASE64_TIDAK_VALID',
      'data_base64 bukan Base64 yang valid.'
    );
  }
}

function normalizeMimeType_(value) {
  var mime = requireString_(value, 'mime', 100).toLowerCase();
  if (mime === 'image/jpg') {
    mime = 'image/jpeg';
  }

  var allowed = {
    'image/jpeg': true,
    'image/png': true
  };

  if (!allowed[mime]) {
    throw new ApiError(
      'MIME_TIDAK_DIDUKUNG',
      'Format foto harus image/jpeg atau image/png.'
    );
  }

  return mime;
}

function sanitizeFileName_(name) {
  var sanitized = String(name || '')
    .replace(/[^A-Za-z0-9._-]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^\.+|\.+$/g, '');

  if (!sanitized) {
    sanitized = 'foto.jpg';
  }

  return sanitized.substring(0, 180);
}

function normalizeJenisObjek_(value) {
  var text = requireString_(value, 'jenis_objek', 30)
    .toUpperCase()
    .replace(/\s+/g, '_');

  var aliases = {
    KELUARGA: 'KELUARGA',
    FAMILY: 'KELUARGA',
    USAHA: 'USAHA',
    BUSINESS: 'USAHA'
  };

  if (!aliases[text]) {
    throw new ApiError(
      'JENIS_OBJEK_TIDAK_VALID',
      'jenis_objek harus KELUARGA atau USAHA.'
    );
  }

  return aliases[text];
}

function normalizeStatusPendataan_(value) {
  var text = requireString_(value, 'status_pendataan', 40)
    .toUpperCase()
    .replace(/\s+/g, '_');

  var aliases = {
    LENGKAP: 'LENGKAP',
    TERISI_LENGKAP: 'LENGKAP',
    TIDAK_LENGKAP: 'TIDAK_LENGKAP',
    TERISI_TIDAK_LENGKAP: 'TIDAK_LENGKAP'
  };

  if (!aliases[text]) {
    throw new ApiError(
      'STATUS_PENDATAAN_TIDAK_VALID',
      'status_pendataan harus LENGKAP atau TIDAK_LENGKAP.'
    );
  }

  return aliases[text];
}

function normalizeBoolean_(value, fieldName, defaultValue) {
  if (value === null || value === undefined || value === '') {
    return defaultValue;
  }

  if (typeof value === 'boolean') {
    return value;
  }

  if (typeof value === 'number') {
    if (value === 1) {
      return true;
    }
    if (value === 0) {
      return false;
    }
  }

  var text = String(value).trim().toLowerCase();
  if (['true', '1', 'ya', 'yes', 'aktif'].indexOf(text) >= 0) {
    return true;
  }
  if (['false', '0', 'tidak', 'no', 'nonaktif'].indexOf(text) >= 0) {
    return false;
  }

  throw new ApiError(
    'NILAI_BOOLEAN_TIDAK_VALID',
    fieldName + ' harus bernilai true/false.'
  );
}

function normalizeEpochMillis_(value, fieldName) {
  if (value === null || value === undefined || value === '') {
    throw new ApiError(
      'FIELD_WAJIB_KOSONG',
      fieldName + ' wajib diisi.'
    );
  }

  var millis;

  if (typeof value === 'number') {
    millis = value;
  } else {
    var text = String(value).trim();
    if (/^\d+(\.\d+)?$/.test(text)) {
      millis = Number(text);
    } else {
      millis = new Date(text).getTime();
    }
  }

  if (!isFinite(millis) || millis <= 0) {
    throw new ApiError(
      'WAKTU_TIDAK_VALID',
      fieldName + ' harus berupa epoch millis atau tanggal ISO valid.'
    );
  }

  /*
   * Toleransi epoch seconds agar integrasi client lebih aman, tetapi format
   * resmi yang digunakan aplikasi tetap epoch millis.
   */
  if (millis < 100000000000) {
    millis = millis * 1000;
  }

  return Math.round(millis);
}

function requiredNumber_(value, fieldName, minimum, maximum) {
  if (value === null || value === undefined || value === '') {
    throw new ApiError(
      'FIELD_WAJIB_KOSONG',
      fieldName + ' wajib diisi.'
    );
  }

  var number = Number(value);
  if (
    !isFinite(number) ||
    number < minimum ||
    number > maximum
  ) {
    throw new ApiError(
      'ANGKA_TIDAK_VALID',
      fieldName + ' memiliki nilai di luar rentang yang diizinkan.'
    );
  }

  return number;
}

function optionalNumber_(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }

  var number = Number(value);
  return isFinite(number) ? number : null;
}

function toNonNegativeInteger_(value, defaultValue) {
  if (value === null || value === undefined || value === '') {
    return defaultValue;
  }

  var number = Number(value);
  if (!isFinite(number) || number < 0) {
    return defaultValue;
  }

  return Math.floor(number);
}

function parseIntegerParameter_(value, fieldName, defaultValue) {
  if (value === null || value === undefined || value === '') {
    return defaultValue;
  }

  var text = String(value).trim();
  if (!/^\d+$/.test(text)) {
    throw new ApiError(
      'PARAMETER_TIDAK_VALID',
      fieldName + ' harus berupa bilangan bulat non-negatif.'
    );
  }

  return parseInt(text, 10);
}

function requireString_(value, fieldName, maxLength) {
  var text = toText_(value);

  if (!text) {
    throw new ApiError(
      'FIELD_WAJIB_KOSONG',
      fieldName + ' wajib diisi.'
    );
  }

  if (maxLength !== null && maxLength !== undefined) {
    if (text.length > maxLength) {
      throw new ApiError(
        'FIELD_TERLALU_PANJANG',
        fieldName + ' melebihi batas ' + maxLength + ' karakter.'
      );
    }
  }

  return text;
}

function optionalString_(value, fieldName, maxLength) {
  if (value === null || value === undefined || value === '') {
    return '';
  }

  var text = String(value).trim();
  if (text.length > maxLength) {
    throw new ApiError(
      'FIELD_TERLALU_PANJANG',
      fieldName + ' melebihi batas ' + maxLength + ' karakter.'
    );
  }

  return text;
}

function toText_(value) {
  if (value === null || value === undefined) {
    return '';
  }

  return String(value).trim();
}

function requireUuidV4_(value, fieldName) {
  var uuid = requireString_(value, fieldName, 100).toLowerCase();
  var pattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  if (!pattern.test(uuid)) {
    throw new ApiError(
      'UUID_TIDAK_VALID',
      fieldName + ' harus berupa UUID v4 yang valid.'
    );
  }

  return uuid;
}

function requireUuid_(value, fieldName) {
  var uuid = requireString_(value, fieldName, 100).toLowerCase();
  var pattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  if (!pattern.test(uuid)) {
    throw new ApiError(
      'UUID_TIDAK_VALID',
      fieldName + ' harus berupa UUID yang valid.'
    );
  }

  return uuid;
}

function normalizePinHash_(value) {
  var hash = toText_(value).toLowerCase();
  if (hash.indexOf('sha256:') === 0) {
    hash = hash.substring('sha256:'.length);
  }

  return /^[0-9a-f]{64}$/.test(hash) ? hash : '';
}

function sha256Hex_(value) {
  var bytes = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    String(value),
    Utilities.Charset.UTF_8
  );

  return bytes.map(function(byte) {
    var unsignedByte = byte < 0 ? byte + 256 : byte;
    return (unsignedByte < 16 ? '0' : '') +
      unsignedByte.toString(16);
  }).join('');
}

function assertToken_(token) {
  var settings = getSettings_();

  if (!settings.apiToken) {
    throw new ApiError(
      'KONFIGURASI_BELUM_LENGKAP',
      'Script Property API_TOKEN belum diatur.'
    );
  }

  if (!token || !constantTimeEquals_(String(token), settings.apiToken)) {
    throw new ApiError(
      'TOKEN_TIDAK_VALID',
      'Token API tidak valid.'
    );
  }
}

function constantTimeEquals_(left, right) {
  var a = String(left);
  var b = String(right);
  var length = Math.max(a.length, b.length);
  var difference = a.length ^ b.length;

  for (var index = 0; index < length; index++) {
    var leftCode = index < a.length ? a.charCodeAt(index) : 0;
    var rightCode = index < b.length ? b.charCodeAt(index) : 0;
    difference |= leftCode ^ rightCode;
  }

  return difference === 0;
}

function publicErrorMessage_(error) {
  if (error && error.name === 'ApiError') {
    return error.message;
  }

  if (error && error.message) {
    return error.message;
  }

  return 'Kesalahan internal server.';
}

function errorResponseFromException_(error, operation) {
  try {
    Logger.log(
      operation + ': ' +
      (error && error.stack
        ? error.stack
        : publicErrorMessage_(error))
    );
  } catch (loggingError) {
    // Jangan menggagalkan respons API hanya karena logging gagal.
  }

  var isApiError = error && error.name === 'ApiError';
  return jsonResponse_({
    ok: false,
    error_code: isApiError ? error.code : 'INTERNAL_ERROR',
    message: isApiError
      ? error.message
      : 'Terjadi kesalahan internal pada server.'
  });
}

function jsonResponse_(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

function ApiError(code, message) {
  this.name = 'ApiError';
  this.code = code;
  this.message = message;
  this.stack = new Error(message).stack;
}

/* ------------------------------------------------------------------------- *
 * Dashboard web dan laporan PDF                                             *
 * ------------------------------------------------------------------------- *
 *
 * Modul di bawah ini hanya dipanggil melalui route page=dashboard atau
 * google.script.run dari Dashboard.html. Jalur doGet(action=master/cakupan)
 * dan doPost(sync/upload_foto/login) tidak diubah.
 */

function handleDashboardPage_(params) {
  var dashboardToken = toText_(
    params.admin_token || params.dashboard_token
  );
  assertDashboardAccess_(dashboardToken);

  var template = HtmlService.createTemplateFromFile('Dashboard');
  template.dashboardTokenJson = JSON.stringify(dashboardToken)
    .replace(/</g, '\\u003c');

  return template
    .evaluate()
    .setTitle('Dashboard Pendataan Lapangan');
}

function getDashboardBootstrap(request) {
  assertDashboardAccess_(getDashboardToken_(request));

  var masterRows = getMasterRecords_();
  var workers = getDashboardWorkers_();
  var kabupaten = {};
  var kecamatan = {};

  masterRows.forEach(function(master) {
    var kabKey = master.kode_kab || master.kabupaten;
    var kecKey = [
      master.kode_kab,
      master.kode_kec
    ].join('|');

    if (kabKey) {
      kabupaten[kabKey] = {
        kode_kab: master.kode_kab,
        kabupaten: master.kabupaten || master.kode_kab
      };
    }

    if (master.kode_kec) {
      kecamatan[kecKey] = {
        kode_kab: master.kode_kab,
        kabupaten: master.kabupaten,
        kode_kec: master.kode_kec,
        nama_kec: master.nama_kec || master.kode_kec
      };
    }
  });

  return {
    ok: true,
    generated_at: new Date().toISOString(),
    versi_master: getMasterVersion_(masterRows),
    jumlah_sls: masterRows.length,
    jumlah_petugas: workers.length,
    petugas: workers.map(publicDashboardWorker_),
    kabupaten: objectValues_(kabupaten).sort(sortByLabel_),
    kecamatan: objectValues_(kecamatan).sort(sortByLabel_)
  };
}

function getDashboardData(request) {
  assertDashboardAccess_(getDashboardToken_(request));
  var filters = normalizeDashboardFilters_(
    request && request.filters ? request.filters : request
  );

  return buildDashboardData_(filters);
}

function getDashboardToken_(request) {
  if (!request) {
    return '';
  }

  return toText_(
    request.dashboard_token ||
    request.admin_token ||
    request.token
  );
}

function assertDashboardAccess_(suppliedToken) {
  var settings = getSettings_();
  var expectedToken = settings.dashboardToken;
  var token = toText_(suppliedToken);

  if (
    expectedToken &&
    token &&
    constantTimeEquals_(token, expectedToken)
  ) {
    return true;
  }

  var allowedEmails = settings.adminEmails
    .split(/[,;|\s]+/)
    .map(function(email) {
      return String(email || '').trim().toLowerCase();
    })
    .filter(function(email) {
      return email !== '';
    });
  var activeEmail = '';

  try {
    activeEmail = String(
      Session.getActiveUser().getEmail() || ''
    ).trim().toLowerCase();
  } catch (error) {
    activeEmail = '';
  }

  if (
    activeEmail &&
    allowedEmails.indexOf(activeEmail) >= 0
  ) {
    return true;
  }

  if (!expectedToken && allowedEmails.length === 0) {
    throw new ApiError(
      'DASHBOARD_BELUM_DIKONFIGURASI',
      'Isi Script Property DASHBOARD_TOKEN atau ADMIN_EMAILS terlebih dahulu.'
    );
  }

  throw new ApiError(
    'AKSES_DASHBOARD_DITOLAK',
    'Anda tidak memiliki akses ke dashboard admin.'
  );
}

function getDashboardWorkers_() {
  var rows = readSheetObjects_(CONFIG.SHEETS.PETUGAS);
  var seen = {};

  return rows.filter(function(row) {
    var id = toText_(row.id_petugas);
    if (!id || seen[id.toLowerCase()]) {
      return false;
    }
    seen[id.toLowerCase()] = true;
    return true;
  }).map(function(row) {
    return {
      id_petugas: toText_(row.id_petugas),
      nama: toText_(row.nama),
      wilayah_penugasan: toText_(row.wilayah_penugasan),
      aktif: normalizeBoolean_(row.aktif, 'aktif', false)
    };
  });
}

function getDashboardWorkerById_(idPetugas) {
  var normalizedId = requireString_(idPetugas, 'id_petugas', 100);
  var workers = getDashboardWorkers_();
  var found = null;

  workers.some(function(worker) {
    if (
      worker.id_petugas.toLowerCase() ===
      normalizedId.toLowerCase()
    ) {
      found = worker;
      return true;
    }
    return false;
  });

  if (!found) {
    throw new ApiError(
      'PETUGAS_TIDAK_DITEMUKAN',
      'Petugas yang dipilih tidak terdaftar.'
    );
  }

  return found;
}

function publicDashboardWorker_(worker) {
  return {
    id_petugas: worker.id_petugas,
    nama: worker.nama,
    wilayah_penugasan: worker.wilayah_penugasan,
    aktif: worker.aktif
  };
}

function normalizeDashboardFilters_(raw) {
  raw = raw || {};

  var dateFromText = toText_(raw.date_from || raw.dateFrom);
  var dateToText = toText_(raw.date_to || raw.dateTo);
  var dateFrom = parseDashboardDate_(dateFromText, false);
  var dateToExclusive = parseDashboardDate_(dateToText, true);

  if (
    dateFrom &&
    dateToExclusive &&
    dateFrom.getTime() >= dateToExclusive.getTime()
  ) {
    throw new ApiError(
      'RENTANG_TANGGAL_TIDAK_VALID',
      'Tanggal mulai harus lebih awal dari tanggal akhir.'
    );
  }

  return {
    kode_kab: toText_(raw.kode_kab),
    kode_kec: toText_(raw.kode_kec),
    id_petugas: toText_(raw.id_petugas),
    date_from: dateFromText,
    date_to: dateToText,
    dateFrom: dateFrom,
    dateToExclusive: dateToExclusive
  };
}

function parseDashboardDate_(value, exclusiveEnd) {
  if (!value) {
    return null;
  }

  var match = String(value).match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) {
    throw new ApiError(
      'TANGGAL_TIDAK_VALID',
      'Tanggal harus menggunakan format YYYY-MM-DD.'
    );
  }

  var year = parseInt(match[1], 10);
  var month = parseInt(match[2], 10) - 1;
  var day = parseInt(match[3], 10);
  var date = new Date(year, month, day);

  if (
    date.getFullYear() !== year ||
    date.getMonth() !== month ||
    date.getDate() !== day
  ) {
    throw new ApiError(
      'TANGGAL_TIDAK_VALID',
      'Tanggal yang dimasukkan tidak valid.'
    );
  }

  if (exclusiveEnd) {
    date.setDate(date.getDate() + 1);
  }

  return date;
}

function buildDashboardData_(filters) {
  var masterRows = getMasterRecords_();
  var workers = getDashboardWorkers_();
  var selectedWorker = filters.id_petugas
    ? getDashboardWorkerById_(filters.id_petugas)
    : null;
  var masterBySls = {};
  var scopeMaster = [];

  masterRows.forEach(function(master) {
    masterBySls[master.kode_sls] = master;

    if (
      matchesDashboardRegion_(master, filters) &&
      (!selectedWorker ||
        isAssignmentAllowed_(master.kode_sls, selectedWorker))
    ) {
      scopeMaster.push(master);
    }
  });

  var scopeSls = {};
  scopeMaster.forEach(function(master) {
    scopeSls[master.kode_sls] = true;
  });

  var records = [];
  var seenRecords = {};
  var pendataanRows = readSheetObjects_(CONFIG.SHEETS.PENDATAAN);

  pendataanRows.forEach(function(row) {
    var idRecord = toText_(row.id_record);
    var idPetugas = toText_(row.id_petugas);
    var kodeSls = toText_(row.kode_sls);
    var master = masterBySls[kodeSls] || null;

    if (!idRecord || seenRecords[idRecord.toLowerCase()]) {
      return;
    }

    if (
      selectedWorker &&
      idPetugas.toLowerCase() !==
      selectedWorker.id_petugas.toLowerCase()
    ) {
      return;
    }

    if (!matchesDashboardRegion_(master || row, filters)) {
      return;
    }

    if (
      selectedWorker &&
      (!master ||
        !isAssignmentAllowed_(kodeSls, selectedWorker))
    ) {
      return;
    }

    var recordDate = dashboardRecordDate_(row.waktu_pendataan);
    if (
      filters.dateFrom &&
      (!recordDate ||
        recordDate.getTime() < filters.dateFrom.getTime())
    ) {
      return;
    }
    if (
      filters.dateToExclusive &&
      (!recordDate ||
        recordDate.getTime() >= filters.dateToExclusive.getTime())
    ) {
      return;
    }

    if (filters.id_petugas && !scopeSls[kodeSls]) {
      return;
    }

    seenRecords[idRecord.toLowerCase()] = true;
    records.push(normalizeDashboardRecord_(row, master));
  });

  records.sort(function(left, right) {
    return dashboardRecordDateValue_(right) -
      dashboardRecordDateValue_(left);
  });

  var kabupaten = {};
  var kecamatan = {};
  var petugas = {};

  scopeMaster.forEach(function(master) {
    var kabKey = master.kode_kab || master.kabupaten || 'TANPA_KAB';
    var kecKey = [
      master.kode_kab,
      master.kode_kec
    ].join('|');

    addDashboardTarget_(kabupaten, kabKey, {
      kode_kab: master.kode_kab,
      kabupaten: master.kabupaten || master.kode_kab
    }, master);
    addDashboardTarget_(kecamatan, kecKey, {
      kode_kab: master.kode_kab,
      kabupaten: master.kabupaten,
      kode_kec: master.kode_kec,
      nama_kec: master.nama_kec || master.kode_kec
    }, master);
  });

  records.forEach(function(record) {
    var kabKey = record.kode_kab ||
      record.kabupaten ||
      'TANPA_KAB';
    var kecKey = [
      record.kode_kab,
      record.kode_kec
    ].join('|');

    addDashboardRecord_(kabupaten, kabKey, record);
    addDashboardRecord_(kecamatan, kecKey, record);

    var workerKey = record.id_petugas || 'TANPA_PETUGAS';
    if (!petugas[workerKey]) {
      petugas[workerKey] = {
        id_petugas: workerKey,
        nama: record.nama_petugas || workerKey,
        target: 0,
        terdata: 0,
        jumlah_sls: 0,
        lengkap: 0,
        tidak_lengkap: 0,
        mock: 0,
        flag_waktu: 0
      };
    }
    addDashboardRecord_(petugas, workerKey, record);
  });

  workers.forEach(function(worker) {
    if (
      selectedWorker &&
      worker.id_petugas.toLowerCase() !==
      selectedWorker.id_petugas.toLowerCase()
    ) {
      return;
    }

    var workerTargetRows = scopeMaster.filter(function(master) {
      return isAssignmentAllowed_(master.kode_sls, worker);
    });
    var workerKey = worker.id_petugas;
    if (!petugas[workerKey]) {
      petugas[workerKey] = {
        id_petugas: worker.id_petugas,
        nama: worker.nama || worker.id_petugas,
        target: 0,
        terdata: 0,
        jumlah_sls: 0,
        lengkap: 0,
        tidak_lengkap: 0,
        mock: 0,
        flag_waktu: 0
      };
    }

    workerTargetRows.forEach(function(master) {
      petugas[workerKey].target += master.target_responden;
      petugas[workerKey].jumlah_sls += 1;
    });
    petugas[workerKey].nama = worker.nama || worker.id_petugas;
  });

  var summary = {
    target: sumDashboardField_(scopeMaster, 'target_responden'),
    terdata: records.length,
    jumlah_record: records.length,
    jumlah_sls: scopeMaster.length,
    jumlah_petugas: objectValues_(petugas).length,
    lengkap: countDashboardStatus_(records, 'LENGKAP'),
    tidak_lengkap: countDashboardStatus_(
      records,
      'TIDAK_LENGKAP'
    ),
    mock: records.filter(function(record) {
      return record.is_mock;
    }).length,
    flag_waktu: records.filter(function(record) {
      return Boolean(record.flag_waktu);
    }).length
  };
  summary.persen_cakupan = coveragePercent_(
    summary.terdata,
    summary.target
  );

  return {
    ok: true,
    generated_at: new Date().toISOString(),
    filters: {
      kode_kab: filters.kode_kab,
      kode_kec: filters.kode_kec,
      id_petugas: filters.id_petugas,
      date_from: filters.date_from,
      date_to: filters.date_to
    },
    summary: summary,
    by_kabupaten: objectValues_(kabupaten)
      .map(publicDashboardAggregate_)
      .sort(sortDashboardAggregate_),
    by_kecamatan: objectValues_(kecamatan)
      .map(publicDashboardAggregate_)
      .sort(sortDashboardAggregate_),
    by_petugas: objectValues_(petugas)
      .map(publicDashboardAggregate_)
      .sort(sortDashboardAggregate_),
    latest: records.slice(0, 100)
  };
}

function matchesDashboardRegion_(row, filters) {
  if (!row) {
    return false;
  }

  if (
    filters.kode_kab &&
    toText_(row.kode_kab) !== filters.kode_kab
  ) {
    return false;
  }

  if (
    filters.kode_kec &&
    toText_(row.kode_kec) !== filters.kode_kec
  ) {
    return false;
  }

  return true;
}

function addDashboardTarget_(map, key, identity, master) {
  if (!map[key]) {
    map[key] = {
      kode_kab: identity.kode_kab || '',
      kabupaten: identity.kabupaten || '',
      kode_kec: identity.kode_kec || '',
      nama_kec: identity.nama_kec || '',
      target: 0,
      terdata: 0,
      jumlah_sls: 0,
      lengkap: 0,
      tidak_lengkap: 0,
      mock: 0,
      flag_waktu: 0
    };
  }

  map[key].target += toNonNegativeInteger_(
    master.target_responden,
    0
  );
  map[key].jumlah_sls += 1;
}

function addDashboardRecord_(map, key, record) {
  if (!map[key]) {
    map[key] = {
      kode_kab: record.kode_kab || '',
      kabupaten: record.kabupaten || '',
      kode_kec: record.kode_kec || '',
      nama_kec: record.nama_kec || '',
      id_petugas: record.id_petugas || '',
      nama: record.nama_petugas || record.id_petugas || '',
      target: 0,
      terdata: 0,
      jumlah_sls: 0,
      lengkap: 0,
      tidak_lengkap: 0,
      mock: 0,
      flag_waktu: 0
    };
  }

  map[key].terdata += 1;

  if (record.status_pendataan === 'LENGKAP') {
    map[key].lengkap += 1;
  }
  if (record.status_pendataan === 'TIDAK_LENGKAP') {
    map[key].tidak_lengkap += 1;
  }
  if (record.is_mock) {
    map[key].mock += 1;
  }
  if (record.flag_waktu) {
    map[key].flag_waktu += 1;
  }
}

function publicDashboardAggregate_(aggregate) {
  return {
    kode_kab: aggregate.kode_kab || '',
    kabupaten: aggregate.kabupaten || '',
    kode_kec: aggregate.kode_kec || '',
    nama_kec: aggregate.nama_kec || '',
    id_petugas: aggregate.id_petugas || '',
    nama: aggregate.nama || '',
    target: aggregate.target || 0,
    terdata: aggregate.terdata || 0,
    persen_cakupan: coveragePercent_(
      aggregate.terdata || 0,
      aggregate.target || 0
    ),
    jumlah_sls: aggregate.jumlah_sls || 0,
    lengkap: aggregate.lengkap || 0,
    tidak_lengkap: aggregate.tidak_lengkap || 0,
    mock: aggregate.mock || 0,
    flag_waktu: aggregate.flag_waktu || 0
  };
}

function sortDashboardAggregate_(left, right) {
  var leftLabel = toText_(
    left.kabupaten ||
    left.nama_kec ||
    left.nama ||
    left.id_petugas
  ).toLowerCase();
  var rightLabel = toText_(
    right.kabupaten ||
    right.nama_kec ||
    right.nama ||
    right.id_petugas
  ).toLowerCase();

  return leftLabel < rightLabel ? -1 : leftLabel > rightLabel ? 1 : 0;
}

function sortByLabel_(left, right) {
  var leftLabel = toText_(
    left.kabupaten || left.nama_kec || left.nama
  ).toLowerCase();
  var rightLabel = toText_(
    right.kabupaten || right.nama_kec || right.nama
  ).toLowerCase();
  return leftLabel < rightLabel ? -1 : leftLabel > rightLabel ? 1 : 0;
}

function sumDashboardField_(rows, fieldName) {
  return rows.reduce(function(total, row) {
    return total + toNonNegativeInteger_(row[fieldName], 0);
  }, 0);
}

function countDashboardStatus_(records, status) {
  return records.filter(function(record) {
    return record.status_pendataan === status;
  }).length;
}

function coveragePercent_(terdata, target) {
  var safeTarget = Number(target) || 0;
  var safeTerdata = Number(terdata) || 0;
  return safeTarget > 0
    ? Math.round((safeTerdata / safeTarget) * 10000) / 100
    : 0;
}

function normalizeDashboardRecord_(row, master) {
  var date = dashboardRecordDate_(row.waktu_pendataan);
  var isMock = false;

  try {
    isMock = normalizeBoolean_(row.is_mock, 'is_mock', false);
  } catch (error) {
    isMock = String(row.is_mock || '').toLowerCase() === 'true';
  }

  return {
    id_record: toText_(row.id_record),
    id_petugas: toText_(row.id_petugas),
    nama_petugas: '',
    kode_kab: master
      ? master.kode_kab
      : toText_(row.kode_kab),
    kabupaten: master
      ? master.kabupaten
      : toText_(row.kabupaten),
    kode_kec: master
      ? master.kode_kec
      : toText_(row.kode_kec),
    nama_kec: master
      ? master.nama_kec
      : toText_(row.nama_kec),
    kode_desa: master
      ? master.kode_desa
      : toText_(row.kode_desa),
    nama_desa: master
      ? master.nama_desa
      : toText_(row.nama_desa),
    kode_sls: master
      ? master.kode_sls
      : toText_(row.kode_sls),
    nama_sls: master
      ? master.nama_sls
      : toText_(row.nama_sls),
    jenis_objek: toText_(row.jenis_objek),
    nama_objek: toText_(row.nama_objek),
    alamat: toText_(row.alamat),
    status_pendataan: toText_(row.status_pendataan)
      .toUpperCase(),
    catatan: toText_(row.catatan),
    latitude: optionalNumber_(row.latitude),
    longitude: optionalNumber_(row.longitude),
    akurasi_m: optionalNumber_(row.akurasi_m),
    is_mock: isMock,
    waktu_pendataan: date
      ? date.toISOString()
      : toText_(row.waktu_pendataan),
    flag_waktu: toText_(row.flag_waktu),
    foto_count: [
      row.url_foto_1,
      row.url_foto_2,
      row.url_foto_3
    ].filter(function(url) {
      return toText_(url) !== '';
    }).length
  };
}

function dashboardRecordDate_(value) {
  if (value instanceof Date) {
    return isNaN(value.getTime()) ? null : value;
  }

  if (typeof value === 'number') {
    var numberDate = new Date(
      value < 100000000000 ? value * 1000 : value
    );
    return isNaN(numberDate.getTime()) ? null : numberDate;
  }

  var text = toText_(value);
  if (!text) {
    return null;
  }

  var date = new Date(text);
  return isNaN(date.getTime()) ? null : date;
}

function dashboardRecordDateValue_(record) {
  var date = dashboardRecordDate_(record.waktu_pendataan);
  return date ? date.getTime() : 0;
}

function objectValues_(object) {
  return Object.keys(object).map(function(key) {
    return object[key];
  });
}

/**
 * Menambah atau memperbarui petugas dari dashboard.
 * PIN tidak pernah dikembalikan ke browser dan tidak pernah disimpan mentah.
 */
function saveDashboardPetugas(request) {
  assertDashboardAccess_(getDashboardToken_(request));
  request = request || {};

  var idPetugas = requireString_(
    request.id_petugas,
    'id_petugas',
    50
  );
  if (!/^[A-Za-z0-9_-]+$/.test(idPetugas)) {
    throw new ApiError(
      'ID_PETUGAS_TIDAK_VALID',
      'Kode petugas hanya boleh berisi huruf, angka, garis bawah, atau tanda hubung.'
    );
  }

  var nama = requireString_(request.nama, 'nama', 150);
  var wilayah = optionalString_(
    request.wilayah_penugasan,
    'wilayah_penugasan',
    2000
  );
  var aktif = normalizeBoolean_(
    request.aktif,
    'aktif',
    true
  );
  var pin = request.pin === undefined || request.pin === null
    ? ''
    : String(request.pin).trim();

  if (pin && pin.length > 100) {
    throw new ApiError(
      'PIN_TERLALU_PANJANG',
      'PIN melebihi batas 100 karakter.'
    );
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new ApiError(
      'SERVER_SIBUK',
      'Server sedang memproses request lain. Silakan coba lagi.'
    );
  }

  try {
    var sheet = getSheetOrThrow_(CONFIG.SHEETS.PETUGAS);
    var headers = getSheetHeaders_(sheet);
    assertRequiredHeaders_(
      headers,
      SHEET_HEADERS.PETUGAS,
      CONFIG.SHEETS.PETUGAS
    );
    var rowsWithNumbers = readSheetObjectRowsWithNumbers_(sheet);
    var rows = rowsWithNumbers.map(function(item) {
      return item.object;
    });
    var rowNumber = -1;
    var existing = null;

    rowsWithNumbers.forEach(function(item) {
      var row = item.object;
      if (
        toText_(row.id_petugas).toLowerCase() ===
        idPetugas.toLowerCase()
      ) {
        rowNumber = item.rowNumber;
        existing = row;
      }
    });

    if (rowNumber < 0 && !pin) {
      throw new ApiError(
        'PIN_WAJIB',
        'PIN wajib diisi saat menambahkan petugas baru.'
      );
    }

    var fields = {
      id_petugas: existing
        ? toText_(existing.id_petugas)
        : idPetugas,
      nama: nama,
      wilayah_penugasan: wilayah,
      aktif: aktif
    };
    if (pin) {
      fields.hash_pin = sha256Hex_(pin);
    }

    if (rowNumber < 0) {
      var newRow = headers.map(function(header) {
        return Object.prototype.hasOwnProperty.call(fields, header)
          ? fields[header]
          : '';
      });
      sheet
        .getRange(sheet.getLastRow() + 1, 1, 1, headers.length)
        .setValues([newRow]);
    } else {
      writeDashboardFields_(sheet, rowNumber, headers, fields);
    }

    return {
      ok: true,
      mode: existing ? 'updated' : 'created',
      petugas: {
        id_petugas: fields.id_petugas,
        nama: fields.nama,
        wilayah_penugasan: fields.wilayah_penugasan,
        aktif: fields.aktif
      }
    };
  } finally {
    lock.releaseLock();
  }
}

function writeDashboardFields_(sheet, rowNumber, headers, fields) {
  Object.keys(fields).forEach(function(fieldName) {
    var column = headers.indexOf(fieldName);
    if (column >= 0) {
      sheet.getRange(rowNumber, column + 1).setValue(
        fields[fieldName]
      );
    }
  });
}

/**
 * Import master wilayah secara aman.
 *
 * Mode upsert berdasarkan kode_sls:
 * - kode_sls yang sudah ada diperbarui;
 * - kode_sls baru ditambahkan;
 * - baris lama yang tidak ada di file import tidak dihapus.
 */
function importDashboardMaster(request) {
  assertDashboardAccess_(getDashboardToken_(request));
  request = request || {};
  var records = request.records;

  if (!Array.isArray(records) || records.length === 0) {
    throw new ApiError(
      'MASTER_KOSONG',
      'Minimal satu baris master wilayah harus dikirim.'
    );
  }
  if (records.length > CONFIG.MAX_MASTER_IMPORT_ROWS) {
    throw new ApiError(
      'MASTER_TERLALU_BESAR',
      'Jumlah baris import melebihi batas 10.000 baris.'
    );
  }

  var normalizedRecords = records.map(
    normalizeDashboardMasterInput_
  );
  var duplicateCodes = {};
  normalizedRecords.forEach(function(record) {
    if (duplicateCodes[record.kode_sls]) {
      throw new ApiError(
        'MASTER_DUPLIKAT_IMPORT',
        'kode_sls duplikat pada file import: ' + record.kode_sls
      );
    }
    duplicateCodes[record.kode_sls] = true;
  });

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(CONFIG.LOCK_TIMEOUT_MS)) {
    throw new ApiError(
      'SERVER_SIBUK',
      'Server sedang memproses request lain. Silakan coba lagi.'
    );
  }

  try {
    var sheet = getSheetOrThrow_(CONFIG.SHEETS.MASTER_WILAYAH);
    var headers = getSheetHeaders_(sheet);
    assertRequiredHeaders_(
      headers,
      SHEET_HEADERS.MASTER_WILAYAH,
      CONFIG.SHEETS.MASTER_WILAYAH
    );
    var existingRowsWithNumbers =
      readSheetObjectRowsWithNumbers_(sheet);
    var existingRows = existingRowsWithNumbers.map(function(item) {
      return item.object;
    });
    var existingByCode = {};
    existingRowsWithNumbers.forEach(function(item) {
      var row = item.object;
      var code = toText_(row.kode_sls);
      if (code) {
        if (existingByCode[code]) {
          throw new ApiError(
            'MASTER_DUPLIKAT',
            'kode_sls duplikat pada master_wilayah: ' + code
          );
        }
        existingByCode[code] = {
          row: row,
          rowNumber: item.rowNumber
        };
      }
    });

    var nextVersion = getMasterVersion_(
      getMasterRecords_()
    ) + 1;
    var updated = 0;
    var inserted = 0;

    normalizedRecords.forEach(function(record) {
      record.versi_master = nextVersion;
      var existing = existingByCode[record.kode_sls];

      if (existing) {
        writeDashboardFields_(
          sheet,
          existing.rowNumber,
          headers,
          record
        );
        updated += 1;
      } else {
        var newRow = headers.map(function(header) {
          return Object.prototype.hasOwnProperty.call(record, header)
            ? record[header]
            : '';
        });
        sheet
          .getRange(sheet.getLastRow() + 1, 1, 1, headers.length)
          .setValues([newRow]);
        inserted += 1;
      }
    });

    return {
      ok: true,
      versi_master: nextVersion,
      updated: updated,
      inserted: inserted,
      unchanged_rows: Math.max(
        existingRows.length - updated,
        0
      )
    };
  } finally {
    lock.releaseLock();
  }
}

function normalizeDashboardMasterInput_(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new ApiError(
      'MASTER_BARIS_TIDAK_VALID',
      'Setiap baris master harus berupa object.'
    );
  }

  var targetValue = raw.target_responden;
  if (
    targetValue === undefined ||
    targetValue === null ||
    String(targetValue).trim() === ''
  ) {
    throw new ApiError(
      'TARGET_WAJIB',
      'target_responden wajib diisi untuk setiap SLS.'
    );
  }
  var target = Number(targetValue);
  if (
    !isFinite(target) ||
    target < 0 ||
    Math.floor(target) !== target
  ) {
    throw new ApiError(
      'TARGET_TIDAK_VALID',
      'target_responden harus bilangan bulat non-negatif.'
    );
  }

  var latitude = optionalNumber_(raw.lat_centroid);
  var longitude = optionalNumber_(raw.lon_centroid);
  if (
    latitude !== null &&
    (latitude < -90 || latitude > 90)
  ) {
    throw new ApiError(
      'LATITUDE_TIDAK_VALID',
      'lat_centroid harus berada antara -90 dan 90.'
    );
  }
  if (
    longitude !== null &&
    (longitude < -180 || longitude > 180)
  ) {
    throw new ApiError(
      'LONGITUDE_TIDAK_VALID',
      'lon_centroid harus berada antara -180 dan 180.'
    );
  }

  return {
    kode_kab: requireString_(
      raw.kode_kab,
      'kode_kab',
      50
    ),
    kabupaten: requireString_(
      raw.kabupaten,
      'kabupaten',
      150
    ),
    kode_kec: requireString_(
      raw.kode_kec,
      'kode_kec',
      50
    ),
    nama_kec: requireString_(
      raw.nama_kec,
      'nama_kec',
      150
    ),
    kode_desa: requireString_(
      raw.kode_desa,
      'kode_desa',
      50
    ),
    nama_desa: requireString_(
      raw.nama_desa,
      'nama_desa',
      150
    ),
    kode_sls: requireString_(
      raw.kode_sls,
      'kode_sls',
      50
    ),
    nama_sls: requireString_(
      raw.nama_sls,
      'nama_sls',
      200
    ),
    target_responden: target,
    lat_centroid: latitude === null ? '' : latitude,
    lon_centroid: longitude === null ? '' : longitude,
    versi_master: 0
  };
}

/**
 * Membuat PDF laporan untuk satu petugas.
 *
 * Laporan dibuat di Google Docs sementara, diekspor menjadi PDF, lalu
 * dokumen sementara dipindahkan ke trash. File PDF final disimpan pada
 * REPORT_FOLDER_ID; jika property itu kosong, digunakan DRIVE_FOLDER_ID
 * sebagai fallback agar instalasi lama tetap dapat mencoba fitur ini.
 */
function generatePetugasReport(request) {
  assertDashboardAccess_(getDashboardToken_(request));
  var options = normalizeReportOptions_(request);
  var dataset = buildPetugasReportDataset_(options);

  if (dataset.records.length > CONFIG.MAX_REPORT_RECORDS) {
    throw new ApiError(
      'LAPORAN_TERLALU_BESAR',
      'Laporan berisi ' + dataset.records.length +
      ' record. Batasi periode atau wilayah agar maksimal ' +
      CONFIG.MAX_REPORT_RECORDS + ' record.'
    );
  }

  if (
    options.includePhotos &&
    dataset.photoCount > CONFIG.MAX_REPORT_PHOTOS
  ) {
    throw new ApiError(
      'FOTO_LAPORAN_TERLALU_BANYAK',
      'Laporan memiliki ' + dataset.photoCount +
      ' foto. Batasi periode atau matikan pilihan Sertakan Foto; ' +
      'maksimal ' + CONFIG.MAX_REPORT_PHOTOS + ' foto per PDF.'
    );
  }

  var reportFolder = getReportFolder_();
  var generatedAt = new Date();
  var periodPart = options.date_from && options.date_to
    ? options.date_from + '_' + options.date_to
    : options.date_from
      ? 'mulai_' + options.date_from
      : options.date_to
        ? 'sampai_' + options.date_to
        : 'seluruh_periode';
  var baseName = sanitizeFileName_(
    'Laporan_Pendataan_' +
    dataset.worker.id_petugas + '_' +
    periodPart + '_' +
    Utilities.formatDate(
      generatedAt,
      getSettings_().timeZone,
      'yyyyMMdd_HHmmss'
    )
  );
  var temporaryDocument = null;
  var temporaryFile = null;

  try {
    temporaryDocument = DocumentApp.create(baseName);
    temporaryFile = DriveApp.getFileById(
      temporaryDocument.getId()
    );
    buildPetugasReportDocument_(
      temporaryDocument,
      dataset,
      options,
      generatedAt
    );
    temporaryDocument.saveAndClose();

    var pdfBlob = temporaryFile
      .getAs(MimeType.PDF)
      .setName(baseName + '.pdf');
    var pdfFile = reportFolder.createFile(pdfBlob);
    pdfFile.setDescription(
      'Laporan pendataan per petugas | ' +
      'id_petugas=' + dataset.worker.id_petugas +
      ' | dibuat=' + generatedAt.toISOString()
    );

    temporaryFile.setTrashed(true);

    var downloadUrl =
      'https://drive.google.com/uc?export=download&id=' +
      encodeURIComponent(pdfFile.getId());

    return {
      ok: true,
      nama_file: pdfFile.getName(),
      file_id: pdfFile.getId(),
      url: pdfFile.getUrl(),
      download_url: downloadUrl,
      id_petugas: dataset.worker.id_petugas,
      jumlah_record: dataset.records.length,
      jumlah_foto: dataset.photoCount,
      dibuat_pada: generatedAt.toISOString()
    };
  } catch (error) {
    if (temporaryFile) {
      try {
        temporaryFile.setTrashed(true);
      } catch (trashError) {
        Logger.log(
          'Gagal membersihkan dokumen sementara: ' +
          publicErrorMessage_(trashError)
        );
      }
    }
    throw error;
  }
}

function normalizeReportOptions_(request) {
  request = request || {};
  var filters = normalizeDashboardFilters_(request.filters || request);
  var includePhotos = normalizeBoolean_(
    request.include_photos !== undefined
      ? request.include_photos
      : request.includePhotos,
    'include_photos',
    false
  );

  return {
    id_petugas: requireString_(
      request.id_petugas ||
      (request.filters && request.filters.id_petugas),
      'id_petugas',
      100
    ),
    kode_kab: filters.kode_kab,
    kode_kec: filters.kode_kec,
    date_from: filters.date_from,
    date_to: filters.date_to,
    dateFrom: filters.dateFrom,
    dateToExclusive: filters.dateToExclusive,
    includePhotos: includePhotos
  };
}

function buildPetugasReportDataset_(options) {
  var worker = getDashboardWorkerById_(options.id_petugas);
  var masterRows = getMasterRecords_();
  var masterBySls = {};
  var scopeMaster = [];

  masterRows.forEach(function(master) {
    masterBySls[master.kode_sls] = master;
    if (
      matchesDashboardRegion_(master, options) &&
      isAssignmentAllowed_(master.kode_sls, worker)
    ) {
      scopeMaster.push(master);
    }
  });

  var seenRecords = {};
  var records = [];
  var rows = readSheetObjects_(CONFIG.SHEETS.PENDATAAN);

  rows.forEach(function(row) {
    var idRecord = toText_(row.id_record);
    var idPetugas = toText_(row.id_petugas);
    var kodeSls = toText_(row.kode_sls);
    var master = masterBySls[kodeSls] || null;

    if (
      !idRecord ||
      seenRecords[idRecord.toLowerCase()] ||
      idPetugas.toLowerCase() !==
      worker.id_petugas.toLowerCase()
    ) {
      return;
    }

    if (!matchesDashboardRegion_(master || row, options)) {
      return;
    }
    if (master && !isAssignmentAllowed_(kodeSls, worker)) {
      return;
    }

    var date = dashboardRecordDate_(row.waktu_pendataan);
    if (
      options.dateFrom &&
      (!date || date.getTime() < options.dateFrom.getTime())
    ) {
      return;
    }
    if (
      options.dateToExclusive &&
      (!date ||
        date.getTime() >= options.dateToExclusive.getTime())
    ) {
      return;
    }

    seenRecords[idRecord.toLowerCase()] = true;
    var normalized = normalizeDashboardRecord_(row, master);
    normalized.nama_petugas = worker.nama;
    normalized.photo_items = [];

    for (var index = 1; index <= 3; index++) {
      var photoUrl = toText_(row['url_foto_' + index]);
      var photoId = getDriveFileIdFromUrl_(photoUrl);
      if (photoUrl || photoId) {
        normalized.photo_items.push({
          urutan: index,
          url: photoUrl,
          file_id: photoId
        });
      }
    }
    normalized.foto_count = normalized.photo_items.length;
    records.push(normalized);
  });

  records.sort(function(left, right) {
    return dashboardRecordDateValue_(right) -
      dashboardRecordDateValue_(left);
  });

  var byKabupaten = {};
  var byKecamatan = {};
  var bySls = {};

  scopeMaster.forEach(function(master) {
    var kabKey = master.kode_kab || master.kabupaten || 'TANPA_KAB';
    var kecKey = [
      master.kode_kab,
      master.kode_kec
    ].join('|');
    var slsKey = master.kode_sls;

    addDashboardTarget_(byKabupaten, kabKey, {
      kode_kab: master.kode_kab,
      kabupaten: master.kabupaten
    }, master);
    addDashboardTarget_(byKecamatan, kecKey, {
      kode_kab: master.kode_kab,
      kabupaten: master.kabupaten,
      kode_kec: master.kode_kec,
      nama_kec: master.nama_kec
    }, master);

    if (!bySls[slsKey]) {
      bySls[slsKey] = {
        kode_kab: master.kode_kab,
        kabupaten: master.kabupaten,
        kode_kec: master.kode_kec,
        nama_kec: master.nama_kec,
        kode_desa: master.kode_desa,
        nama_desa: master.nama_desa,
        kode_sls: master.kode_sls,
        nama_sls: master.nama_sls,
        target: 0,
        terdata: 0,
        lengkap: 0,
        tidak_lengkap: 0
      };
    }
    bySls[slsKey].target += master.target_responden;
  });

  records.forEach(function(record) {
    var kabKey = record.kode_kab || record.kabupaten || 'TANPA_KAB';
    var kecKey = [
      record.kode_kab,
      record.kode_kec
    ].join('|');
    var slsKey = record.kode_sls || 'TANPA_SLS';

    addDashboardRecord_(byKabupaten, kabKey, record);
    addDashboardRecord_(byKecamatan, kecKey, record);

    if (!bySls[slsKey]) {
      bySls[slsKey] = {
        kode_kab: record.kode_kab,
        kabupaten: record.kabupaten,
        kode_kec: record.kode_kec,
        nama_kec: record.nama_kec,
        kode_desa: record.kode_desa,
        nama_desa: record.nama_desa,
        kode_sls: record.kode_sls,
        nama_sls: record.nama_sls,
        target: 0,
        terdata: 0,
        lengkap: 0,
        tidak_lengkap: 0
      };
    }
    bySls[slsKey].terdata += 1;
    if (record.status_pendataan === 'LENGKAP') {
      bySls[slsKey].lengkap += 1;
    }
    if (record.status_pendataan === 'TIDAK_LENGKAP') {
      bySls[slsKey].tidak_lengkap += 1;
    }
  });

  var summary = {
    target: sumDashboardField_(scopeMaster, 'target_responden'),
    terdata: records.length,
    jumlah_record: records.length,
    lengkap: countDashboardStatus_(records, 'LENGKAP'),
    tidak_lengkap: countDashboardStatus_(
      records,
      'TIDAK_LENGKAP'
    ),
    mock: records.filter(function(record) {
      return record.is_mock;
    }).length,
    flag_waktu: records.filter(function(record) {
      return Boolean(record.flag_waktu);
    }).length
  };
  summary.persen_cakupan = coveragePercent_(
    summary.terdata,
    summary.target
  );

  var photoCount = records.reduce(function(total, record) {
    return total + record.photo_items.filter(function(photo) {
      return Boolean(photo.file_id);
    }).length;
  }, 0);

  return {
    worker: worker,
    summary: summary,
    records: records,
    photoCount: photoCount,
    by_kabupaten: objectValues_(byKabupaten)
      .map(publicDashboardAggregate_)
      .sort(sortDashboardAggregate_),
    by_kecamatan: objectValues_(byKecamatan)
      .map(publicDashboardAggregate_)
      .sort(sortDashboardAggregate_),
    by_sls: objectValues_(bySls).sort(function(left, right) {
      return toText_(left.nama_sls).localeCompare(
        toText_(right.nama_sls)
      );
    })
  };
}

function buildPetugasReportDocument_(
  document,
  dataset,
  options,
  generatedAt
) {
  var body = document.getBody();
  body.clear();
  body.setPageWidth(841.89);
  body.setPageHeight(595.28);
  body.setMarginTop(32);
  body.setMarginBottom(32);
  body.setMarginLeft(36);
  body.setMarginRight(36);

  var title = body
    .appendParagraph('LAPORAN HASIL PENDATAAN LAPANGAN');
  title
    .setHeading(DocumentApp.ParagraphHeading.TITLE)
    .setAlignment(DocumentApp.HorizontalAlignment.CENTER);
  styleReportParagraph_(title, {
    bold: true,
    color: '#0B3558'
  });

  var subtitle = body.appendParagraph('SENSUS EKONOMI 2026');
  subtitle
    .setAlignment(DocumentApp.HorizontalAlignment.CENTER);
  styleReportParagraph_(subtitle, {
    bold: true,
    color: '#0D9488'
  });

  var printedAt = body.appendParagraph(
    'Dicetak pada ' +
    formatReportDateTime_(generatedAt) +
    ' | Sumber: Spreadsheet hasil sinkronisasi aplikasi mobile'
  );
  printedAt.setAlignment(DocumentApp.HorizontalAlignment.CENTER);
  styleReportParagraph_(printedAt, {
    size: 8,
    color: '#64748B'
  });

  body.appendHorizontalRule();

  var workerTable = body.appendTable([
    ['IDENTITAS PETUGAS', 'NILAI'],
    ['Kode petugas', dataset.worker.id_petugas],
    ['Nama petugas', dataset.worker.nama || '-'],
    ['Wilayah penugasan', dataset.worker.wilayah_penugasan || 'Semua wilayah'],
    ['Periode', reportPeriodLabel_(options)]
  ]);
  styleReportTable_(workerTable);

  var summaryHeading = body.appendParagraph('RINGKASAN PROGRES');
  summaryHeading.setHeading(DocumentApp.ParagraphHeading.HEADING1);
  styleReportParagraph_(summaryHeading, {color: '#0B3558'});

  var summary = dataset.summary;
  var summaryTable = body.appendTable([
    ['Indikator', 'Nilai'],
    ['Target responden', formatReportNumber_(summary.target)],
    ['Jumlah terdata', formatReportNumber_(summary.terdata)],
    ['Cakupan', formatReportPercent_(summary.persen_cakupan)],
    ['Terisi lengkap', formatReportNumber_(summary.lengkap)],
    ['Terisi tidak lengkap', formatReportNumber_(
      summary.tidak_lengkap
    )],
    ['Terdeteksi mock location', formatReportNumber_(summary.mock)],
    ['Flag waktu perangkat', formatReportNumber_(
      summary.flag_waktu
    )]
  ]);
  styleReportTable_(summaryTable);

  var kabupatenHeading = body.appendParagraph(
    'REKAP PER KABUPATEN'
  );
  kabupatenHeading.setHeading(
    DocumentApp.ParagraphHeading.HEADING1
  );
  styleReportParagraph_(kabupatenHeading, {color: '#0B3558'});
  appendReportAggregateTable_(
    body,
    dataset.by_kabupaten,
    ['Kabupaten', 'Target', 'Terdata', 'Cakupan', 'SLS'],
    function(item) {
      return [
        item.kabupaten || item.kode_kab || '-',
        formatReportNumber_(item.target),
        formatReportNumber_(item.terdata),
        formatReportPercent_(item.persen_cakupan),
        formatReportNumber_(item.jumlah_sls)
      ];
    }
  );

  var kecamatanHeading = body.appendParagraph(
    'REKAP PER KECAMATAN'
  );
  kecamatanHeading.setHeading(
    DocumentApp.ParagraphHeading.HEADING1
  );
  styleReportParagraph_(kecamatanHeading, {color: '#0B3558'});
  appendReportAggregateTable_(
    body,
    dataset.by_kecamatan,
    ['Kecamatan', 'Kabupaten', 'Target', 'Terdata', 'Cakupan'],
    function(item) {
      return [
        item.nama_kec || item.kode_kec || '-',
        item.kabupaten || item.kode_kab || '-',
        formatReportNumber_(item.target),
        formatReportNumber_(item.terdata),
        formatReportPercent_(item.persen_cakupan)
      ];
    }
  );

  body.appendPageBreak();
  var slsHeading = body.appendParagraph('REKAP PER SLS');
  slsHeading.setHeading(DocumentApp.ParagraphHeading.HEADING1);
  styleReportParagraph_(slsHeading, {color: '#0B3558'});
  appendReportAggregateTable_(
    body,
    dataset.by_sls,
    ['Kode SLS', 'Nama SLS', 'Kecamatan', 'Target', 'Terdata', 'Cakupan'],
    function(item) {
      return [
        item.kode_sls || '-',
        item.nama_sls || '-',
        item.nama_kec || item.kode_kec || '-',
        formatReportNumber_(item.target),
        formatReportNumber_(item.terdata),
        formatReportPercent_(
          coveragePercent_(item.terdata, item.target)
        )
      ];
    }
  );

  var detailHeading = body.appendParagraph(
    'RINCIAN HASIL PENDATAAN'
  );
  detailHeading.setHeading(
    DocumentApp.ParagraphHeading.HEADING1
  );
  styleReportParagraph_(detailHeading, {color: '#0B3558'});

  var detailRows = [[
    'No',
    'Tanggal',
    'SLS',
    'Objek',
    'Nama objek',
    'Alamat / catatan',
    'Status',
    'Koordinat',
    'Audit'
  ]];
  dataset.records.forEach(function(record, index) {
    detailRows.push([
      String(index + 1),
      formatReportDateTime_(
        dashboardRecordDate_(record.waktu_pendataan)
      ),
      (record.kode_sls || '-') + ' - ' +
        (record.nama_sls || '-'),
      record.jenis_objek || '-',
      record.nama_objek || '-',
      (record.alamat || '-') +
        (record.catatan ? ' | ' + record.catatan : ''),
      record.status_pendataan || '-',
      formatReportCoordinate_(record.latitude, record.longitude),
      reportAuditLabel_(record)
    ]);
  });

  if (dataset.records.length === 0) {
    detailRows.push([
      '-', '-', '-', '-', 'Tidak ada data', '-', '-', '-', '-'
    ]);
  }
  styleReportTable_(body.appendTable(detailRows));

  var note = body.appendParagraph(
    'Catatan: laporan hanya memuat record yang telah tersimpan di server. ' +
    'Record DRAFT atau SIAP_KIRIM yang masih berada di perangkat belum ' +
    'termasuk. Audit mock location dan selisih waktu ditampilkan sebagai ' +
    'peringatan untuk pemeriksaan lanjutan.'
  );
  styleReportParagraph_(note, {
    size: 8,
    color: '#64748B'
  });

  if (options.includePhotos) {
    body.appendPageBreak();
    var photoHeading = body.appendParagraph(
      'LAMPIRAN FOTO PENDATAAN'
    );
    photoHeading.setHeading(
      DocumentApp.ParagraphHeading.HEADING1
    );
    styleReportParagraph_(photoHeading, {color: '#0B3558'});

    var appendedPhotoCount = 0;
    dataset.records.forEach(function(record) {
      if (!record.photo_items || record.photo_items.length === 0) {
        return;
      }

      var caption = body.appendParagraph(
        (record.id_record || '-') + ' | ' +
        (record.nama_sls || record.kode_sls || '-') + ' | ' +
        (record.nama_objek || '-')
      );
      styleReportParagraph_(caption, {
        bold: true,
        size: 9,
        color: '#0B3558'
      });

      var imageParagraph = body.appendParagraph('');
      record.photo_items.forEach(function(photo) {
        if (!photo.file_id) {
          imageParagraph
            .appendText('[Foto tidak memiliki file ID] ');
          return;
        }

        try {
          verifyDriveFileInConfiguredFolder_(photo.file_id);
          var file = DriveApp.getFileById(photo.file_id);
          var image = imageParagraph.appendInlineImage(
            file.getBlob()
          );
          resizeReportImage_(image);
          imageParagraph.appendText('   ');
          appendedPhotoCount += 1;
        } catch (error) {
          imageParagraph.appendText(
            '[Foto tidak dapat diambil] '
          );
        }
      });
    });

    if (appendedPhotoCount === 0) {
      body.appendParagraph(
        'Tidak ada foto yang dapat ditampilkan. Periksa akses file dan ' +
        'DRIVE_FOLDER_ID.'
      );
    }
  }

  body.appendParagraph('');
  var signatureTitle = body.appendParagraph(
    'Petugas,                         Pemeriksa,'
  );
  styleReportParagraph_(signatureTitle, {size: 9});
  body.appendParagraph('\n\n');
  var signatureName = body.appendParagraph(
    (dataset.worker.nama || dataset.worker.id_petugas) +
    '                         ____________________'
  );
  styleReportParagraph_(signatureName, {size: 9});
}

function appendReportAggregateTable_(
  body,
  rows,
  headers,
  rowMapper
) {
  var tableRows = [headers];
  rows.forEach(function(row) {
    tableRows.push(rowMapper(row));
  });

  if (rows.length === 0) {
    tableRows.push(headers.map(function(header, index) {
      return index === 0 ? 'Tidak ada data' : '-';
    }));
  }

  styleReportTable_(body.appendTable(tableRows));
}

function styleReportTable_(table) {
  var rowCount = table.getNumRows();
  if (rowCount < 1) {
    return table;
  }

  var headerCells = table.getRow(0).getCells();
  headerCells.forEach(function(cell) {
    cell.setBackgroundColor('#0B3558');
    cell.editAsText()
      .setForegroundColor('#FFFFFF')
      .setBold(true)
      .setFontSize(8);
  });

  for (var rowIndex = 1; rowIndex < rowCount; rowIndex++) {
    if (rowIndex % 2 === 0) {
      table.getRow(rowIndex).getCells().forEach(function(cell) {
        cell.setBackgroundColor('#F1F5F9');
      });
    }
  }

  return table;
}

function styleReportParagraph_(paragraph, options) {
  options = options || {};
  var text = paragraph.editAsText();
  if (options.bold !== undefined) {
    text.setBold(options.bold);
  }
  if (options.size !== undefined) {
    text.setFontSize(options.size);
  }
  if (options.color) {
    text.setForegroundColor(options.color);
  }
  return paragraph;
}

function resizeReportImage_(image) {
  var width = image.getWidth();
  var height = image.getHeight();
  if (!width || !height) {
    return;
  }

  var scale = Math.min(
    CONFIG.REPORT_PHOTO_MAX_WIDTH / width,
    CONFIG.REPORT_PHOTO_MAX_HEIGHT / height,
    1
  );
  image
    .setWidth(Math.round(width * scale))
    .setHeight(Math.round(height * scale));
}

function getReportFolder_() {
  var settings = getSettings_();
  var folderId = settings.reportFolderId ||
    settings.driveFolderId;

  if (!folderId) {
    throw new ApiError(
      'KONFIGURASI_BELUM_LENGKAP',
      'Isi REPORT_FOLDER_ID atau DRIVE_FOLDER_ID pada Script Properties.'
    );
  }

  try {
    return DriveApp.getFolderById(folderId);
  } catch (error) {
    throw new ApiError(
      'FOLDER_LAPORAN_TIDAK_DAPAT_DIAKSES',
      'Folder laporan PDF tidak dapat dibuka.'
    );
  }
}

function getDriveFileIdFromUrl_(value) {
  var text = toText_(value);
  if (!text) {
    return '';
  }

  if (/^[A-Za-z0-9_-]{10,}$/.test(text)) {
    return text;
  }

  var patterns = [
    /\/d\/([A-Za-z0-9_-]+)/,
    /[?&]id=([A-Za-z0-9_-]+)/,
    /[?&]file_id=([A-Za-z0-9_-]+)/
  ];
  for (var index = 0; index < patterns.length; index++) {
    var match = text.match(patterns[index]);
    if (match && match[1]) {
      return match[1];
    }
  }
  return '';
}

function formatReportDateTime_(value) {
  var date = dashboardRecordDate_(value);
  if (!date) {
    return '-';
  }
  return Utilities.formatDate(
    date,
    getSettings_().timeZone,
    'dd/MM/yyyy HH:mm'
  );
}

function reportPeriodLabel_(options) {
  if (options.date_from && options.date_to) {
    return options.date_from + ' s.d. ' + options.date_to;
  }
  if (options.date_from) {
    return 'Mulai ' + options.date_from;
  }
  if (options.date_to) {
    return 'Sampai ' + options.date_to;
  }
  return 'Seluruh periode';
}

function formatReportNumber_(value) {
  var number = Number(value) || 0;
  return String(Math.round(number));
}

function formatReportPercent_(value) {
  var number = Number(value) || 0;
  return number.toFixed(2).replace('.', ',') + '%';
}

function formatReportCoordinate_(latitude, longitude) {
  if (latitude === null || longitude === null) {
    return '-';
  }
  return Number(latitude).toFixed(6) + ', ' +
    Number(longitude).toFixed(6);
}

function reportAuditLabel_(record) {
  var labels = [];
  if (record.is_mock) {
    labels.push('MOCK');
  }
  if (record.flag_waktu) {
    labels.push('WAKTU');
  }
  return labels.length > 0 ? labels.join(', ') : 'OK';
}
