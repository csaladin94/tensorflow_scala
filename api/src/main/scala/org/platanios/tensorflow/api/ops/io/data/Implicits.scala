/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.ops.io.data

/** Groups together all implicits related to the data API.
  *
  * @author Emmanouil Antonios Platanios
  */
private[io] trait Implicits
    extends BatchDataset.Implicits
        with CacheDataset.Implicits
        with ConcatenatedDataset.Implicits
        with DropDataset.Implicits
        with FilterDataset.Implicits
        with FlatMapDataset.Implicits
        with GroupByWindowDataset.Implicits
        with IgnoreErrorsDataset.Implicits
        with MapDataset.Implicits
        with PaddedBatchDataset.Implicits
        with PrefetchDataset.Implicits
        with RepeatDataset.Implicits
        with ShuffleDataset.Implicits
        with TakeDataset.Implicits
        with ZipDataset.Implicits
