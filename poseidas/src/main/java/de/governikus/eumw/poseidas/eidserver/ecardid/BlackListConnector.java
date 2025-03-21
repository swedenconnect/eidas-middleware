/*
 * Copyright (c) 2020 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except
 * in compliance with the Licence. You may obtain a copy of the Licence at:
 * http://joinup.ec.europa.eu/software/page/eupl Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package de.governikus.eumw.poseidas.eidserver.ecardid;

/**
 * Access object for black list. The complete black list cannot be loaded into memory because it may be too
 * large.
 *
 * @author tautenhahn
 */
public interface BlackListConnector
{

  /**
   * return the sector id this blacklist applies to
   *
   * @return binary value as contained in the original black list.
   */
  public byte[] getSectorID();

  /**
   * Return true if the given card and sector specific ID is on the black list.
   *
   * @param sectorSpecificID must belong the the sector specified by {@link #getSectorID()}
   */
  public boolean contains(byte[] sectorSpecificID);

}
