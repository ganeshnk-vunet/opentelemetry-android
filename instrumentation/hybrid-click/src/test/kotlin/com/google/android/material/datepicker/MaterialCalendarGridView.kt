/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.material.datepicker

import android.content.Context
import android.widget.GridView

/**
 * Test stand-in for Material's real day grid.
 *
 * Declared under Material's own package on purpose: the detector recognizes a calendar day by its
 * parent's **qualified name**, since this module must not depend on `com.google.android.material`.
 * A double with any other name would exercise nothing. This lives only in test sources, and the real
 * class is what ships.
 */
class MaterialCalendarGridView(
    context: Context,
) : GridView(context)
