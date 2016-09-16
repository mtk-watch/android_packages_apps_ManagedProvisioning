/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.task;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.managedprovisioning.model.ProvisioningParams;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DeleteNonRequiredAppsTaskTest extends AndroidTestCase {
    private static final String TEST_DPC_PACKAGE_NAME = "dpc.package.name";
    private static final int TEST_USER_ID = 123;

    private @Mock AbstractProvisioningTask.Callback mCallback;
    private @Mock Context mTestContext;
    private @Mock NonRequiredAppsHelper mHelper;

    private FakePackageManager mPackageManager;

    private Set<String> mDeletedApps;
    private Set<String> mInstalledApplications;
    private DeleteNonRequiredAppsTask mTask;

    @Override
    protected void setUp() throws Exception {
        // this is necessary for mockito to work
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());

        MockitoAnnotations.initMocks(this);

        mPackageManager = new FakePackageManager();

        when(mTestContext.getPackageManager()).thenReturn(mPackageManager);
        when(mTestContext.getFilesDir()).thenReturn(getContext().getFilesDir());

        mDeletedApps = new HashSet<>();
    }

    // We run most methods for device owner only, and we'll assume they also work for profile owner.
    @SmallTest
    public void testNonRequiredAppsAreDeleted() {
        setNonRequiredApps("app.a", "app.b");
        setNewSystemApps("app.a", "app.b");
        setInstalledSystemApps("app.a", "app.b");

        runTask(ACTION_PROVISION_MANAGED_DEVICE, false);

        assertDeletedApps("app.a", "app.b");
        verify(mCallback).onSuccess(mTask);
        verifyNoMoreInteractions(mCallback);
    }

    @SmallTest
    public void testLeaveAllAppsEnabled() {
        runTask(ACTION_PROVISION_MANAGED_DEVICE, true);

        assertDeletedApps();
        verify(mCallback).onSuccess(mTask);
        verifyNoMoreInteractions(mCallback);
    }

    @SmallTest
    public void testEmptyNewSystemApps() {
        setNonRequiredApps("app.a", "app.b");
        setNewSystemApps();
        setInstalledSystemApps("app.c");

        runTask(ACTION_PROVISION_MANAGED_DEVICE, false);

        assertDeletedApps();
        verify(mCallback).onSuccess(mTask);
        verifyNoMoreInteractions(mCallback);
    }

    @SmallTest
    public void testNewSystemAppsFailed() {
        setNonRequiredApps("app.a", "app.b");
        setNewSystemApps(null);
        setInstalledSystemApps("app.a", "app.c");

        runTask(ACTION_PROVISION_MANAGED_DEVICE, false);

        assertDeletedApps();
        verify(mCallback).onError(mTask, 0);
    }

    @SmallTest
    public void testWhenNonRequiredAppsAreNotInstalled() {
        setNonRequiredApps("app.a", "app.b");
        setNewSystemApps("app.a", "app.c");
        setInstalledSystemApps("app.a", "app.c");

        runTask(ACTION_PROVISION_MANAGED_DEVICE, false);

        assertDeletedApps("app.a");
        verify(mCallback).onSuccess(mTask);
        verifyNoMoreInteractions(mCallback);
    }

    @SmallTest
    public void testWhenDeletionFails() {
        setNonRequiredApps("app.a");
        setNewSystemApps("app.a");
        setInstalledSystemApps("app.a");
        mPackageManager.setDeletionSucceeds(false);
        runTask(ACTION_PROVISION_MANAGED_DEVICE, false);
        verify(mCallback).onError(mTask, 0);
        verifyNoMoreInteractions(mCallback);
    }

    private void runTask(String action, boolean leaveAllSystemAppsEnabled) {
        ProvisioningParams params = new ProvisioningParams.Builder()
                .setProvisioningAction(action)
                .setDeviceAdminPackageName(TEST_DPC_PACKAGE_NAME)
                .setLeaveAllSystemAppsEnabled(leaveAllSystemAppsEnabled)
                .build();
        mTask = new DeleteNonRequiredAppsTask(
                mTestContext,
                params,
                mCallback,
                mHelper);
        mTask.run(TEST_USER_ID);
    }

    private void assertDeletedApps(String... appArray) {
        assertEquals(setFromArray(appArray), mDeletedApps);
    }

    private void setNonRequiredApps(String... appArray) {
        when(mHelper.getNonRequiredApps(TEST_USER_ID)).thenReturn(setFromArray(appArray));
    }

    private void setNewSystemApps(String... appArray) {
        when(mHelper.getNewSystemApps(TEST_USER_ID)).thenReturn(setFromArray(appArray));
    }

    private void setInstalledSystemApps(String... installedSystemApps) {
        mInstalledApplications = setFromArray(installedSystemApps);
    }

    private <T> Set<T> setFromArray(T[] array) {
        if (array == null) {
            return null;
        }
        return new HashSet<>(Arrays.asList(array));
    }

    class FakePackageManager extends MockPackageManager {
        private boolean mDeletionSucceeds = true;

        void setDeletionSucceeds(boolean deletionSucceeds) {
            mDeletionSucceeds = deletionSucceeds;
        }

        @Override
        public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer,
                int flags, int userId) {
            if (mDeletionSucceeds) {
                mDeletedApps.add(packageName);
            }
            assertTrue((flags & PackageManager.DELETE_SYSTEM_APP) != 0);
            assertEquals(TEST_USER_ID, userId);

            int resultCode;
            if (mDeletionSucceeds) {
                resultCode = PackageManager.DELETE_SUCCEEDED;
            } else {
                resultCode = PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }

            try {
                observer.packageDeleted(packageName, resultCode);
            } catch (RemoteException e) {
                fail(e.toString());
            }
        }

        @Override
        public PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId)
                throws NameNotFoundException {
            if (mInstalledApplications.contains(packageName)) {
                return new PackageInfo();
            }
            throw new NameNotFoundException();
        }
    }
}
