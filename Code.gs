/**
 * Aplikasi Pendataan Lapangan
 * Google Apps Script Web App API
 *
 * API yang tersedia:
 *   GET  /exec?action=master&versi=7&token=...
 *   GET  /exec?action=cakupan&id_petugas=P001&token=...
 *   POST /exec  { action: "upload_foto", ... }
 *   POST /exec  { action: "sync", ... }
 *
 * Konfigurasi wajib diletakkan pada Script Properties:
 *   SPREADSHEET_ID   = ID Google Spreadsheet
 *   DRIVE_FOLDER_ID  = ID folder Google Drive untuk foto
 *   API_TOKEN        = token rahasia API
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
  LOCK_TIMEOUT_MS: 30 * 1000,
  MAX_TIME_DRIFT_MS: 10 * 60 * 1000,
  DEFAULT_TIMEZONE: 'Asia/Makassar',
  SHEETS: {
    PETUGAS: 'petugas',
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
  MASTER_WILAYAH: [
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
    apiToken: String(
      properties.getProperty('API_TOKEN') || ''
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
    .map(function(row) {
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

      return hasValue ? object : null;
    })
    .filter(function(object) {
      return object !== null;
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
  var assignments = normalizeAssignments_(
    worker.wilayah_penugasan
  );

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
