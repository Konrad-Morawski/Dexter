/*
 * Copyright (C) 2015 Karumi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.karumi.dexter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.karumi.dexter.listener.PermissionDeniedResponse;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;
import com.karumi.dexter.listener.single.PermissionListener;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inner implementation of a dexter instance holding the state of the permissions request
 */
final class DexterInstance {

  private static final int PERMISSIONS_REQUEST_CODE = 42;

  private final Context context;
  private final AndroidPermissionService androidPermissionService;
  private final IntentProvider intentProvider;
  private final Collection<String> pendingPermissions;
  private final MultiplePermissionsReport multiplePermissionsReport;
  private final AtomicBoolean isRequestingPermission;
  private Activity activity;
  private MultiplePermissionsListener listener;

  DexterInstance(Context context, AndroidPermissionService androidPermissionService,
      IntentProvider intentProvider) {
    this.context = context;
    this.androidPermissionService = androidPermissionService;
    this.intentProvider = intentProvider;
    this.pendingPermissions = new TreeSet<>();
    this.multiplePermissionsReport = new MultiplePermissionsReport();
    this.isRequestingPermission = new AtomicBoolean();
  }

  /**
   * Checks the state of a specific permission reporting it when ready to the listener
   *
   * @param listener The class that will be reported when the state of the permission is ready
   * @param permission One of the values found in {@link android.Manifest.permission}
   */
  void checkPermission(PermissionListener listener, String permission) {
    MultiplePermissionsListener adapter =
        new MultiplePermissionsListenerToPermissionListenerAdapter(listener);
    checkPermissions(adapter, Collections.singleton(permission));
  }

  /**
   * Checks the state of a collection of permissions reporting their state to the listener when all
   * of them are resolved
   *
   * @param listener The class that will be reported when the state of all the permissions is ready
   * @param permissions Array of values found in {@link android.Manifest.permission}
   */
  void checkPermissions(MultiplePermissionsListener listener, Collection<String> permissions) {
    checkNoDexterRequestOngoing();
    checkRequestSomePermission(permissions);

    pendingPermissions.clear();
    pendingPermissions.addAll(permissions);
    multiplePermissionsReport.clear();
    this.listener = listener;

    startTransparentActivity();
  }

  /**
   * Method called whenever the inner activity has been created and is ready to be used
   */
  void onActivityCreated(Activity activity) {
    this.activity = activity;

    Collection<String> deniedRequests = new LinkedList<>();
    Collection<String> grantedRequests = new LinkedList<>();

    for (String permission : pendingPermissions) {
      int permissionState = androidPermissionService.checkSelfPermission(activity, permission);
      switch (permissionState) {
        case PackageManager.PERMISSION_DENIED:
          deniedRequests.add(permission);
          break;
        case PackageManager.PERMISSION_GRANTED:
        default:
          grantedRequests.add(permission);
          break;
      }
    }

    handleDeniedPermissions(deniedRequests);
    updatePermissionsAsGranted(grantedRequests);
  }

  /**
   * Method called whenever the permissions has been granted by the user
   */
  void onPermissionRequestGranted(Collection<String> permissions) {
    updatePermissionsAsGranted(permissions);
  }

  /**
   * Method called whenever the permissions has been denied by the user
   */
  void onPermissionRequestDenied(Collection<String> permissions) {
    updatePermissionsAsDenied(permissions);
  }

  /**
   * Method called when the user has been informed with a rationale and agrees to continue
   * with the permission request process
   */
  void onContinuePermissionRequest() {
    requestPermissionsToSystem(pendingPermissions);
  }

  /**
   * Method called when the user has been informed with a rationale and decides to cancel
   * the permission request process
   */
  void onCancelPermissionRequest() {
    updatePermissionsAsDenied(pendingPermissions);
  }

  /**
   * Starts the native request permissions process
   */
  void requestPermissionsToSystem(Collection<String> permissions) {
    androidPermissionService.requestPermissions(activity,
        permissions.toArray(new String[permissions.size()]), PERMISSIONS_REQUEST_CODE);
  }

  private void startTransparentActivity() {
    Intent intent = intentProvider.get(context, DexterActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  private void handleDeniedPermissions(Collection<String> permissions) {
    if (permissions.isEmpty()) {
      return;
    }

    List<PermissionRequest> shouldShowRequestRationalePermissions = new LinkedList<>();

    for (String permission : permissions) {
      if (androidPermissionService.shouldShowRequestPermissionRationale(activity, permission)) {
        shouldShowRequestRationalePermissions.add(new PermissionRequest(permission));
      }
    }

    if (shouldShowRequestRationalePermissions.isEmpty()) {
      requestPermissionsToSystem(permissions);
    } else {
      PermissionRationaleToken permissionToken = new PermissionRationaleToken(this);
      listener.onPermissionRationaleShouldBeShown(shouldShowRequestRationalePermissions,
          permissionToken);
    }
  }

  private void updatePermissionsAsGranted(Collection<String> permissions) {
    for (String permission : permissions) {
      PermissionGrantedResponse response = PermissionGrantedResponse.from(permission);
      multiplePermissionsReport.addGrantedPermissionResponse(response);
    }
    onPermissionsChecked(permissions);
  }

  private void updatePermissionsAsDenied(Collection<String> permissions) {
    for (String permission : permissions) {
      PermissionDeniedResponse response = PermissionDeniedResponse.from(permission,
          !androidPermissionService.shouldShowRequestPermissionRationale(activity, permission));
      multiplePermissionsReport.addDeniedPermissionResponse(response);
    }
    onPermissionsChecked(permissions);
  }

  private void onPermissionsChecked(Collection<String> permissions) {
    if (pendingPermissions.isEmpty()) {
      return;
    }

    pendingPermissions.removeAll(permissions);
    if (pendingPermissions.isEmpty()) {
      activity.finish();
      isRequestingPermission.set(false);
      listener.onPermissionsChecked(multiplePermissionsReport);
    }
  }

  private void checkNoDexterRequestOngoing() {
    if (isRequestingPermission.getAndSet(true)) {
      throw new IllegalStateException("Only one Dexter request at a time is allowed");
    }
  }

  private void checkRequestSomePermission(Collection<String> permissions) {
    if (permissions.isEmpty()) {
      throw new IllegalStateException("Dexter has to be called with at least one permission");
    }
  }
}
